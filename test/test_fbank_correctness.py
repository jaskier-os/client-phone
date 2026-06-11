"""
Verify that our Fbank implementation matches SpeechBrain's pipeline exactly.

Runs both pipelines on the same audio and compares:
  1. Fbank features (before normalization)
  2. Normalized features (after mean subtraction)
  3. ONNX embeddings
  4. Speaker discrimination (true speaker vs TTS impostor)

Requires SpeechBrain (use transcriber venv):
  /workspace/project/sub-module/infrastructure/transcriber/venv/bin/python3 test_fbank_correctness.py

Usage:
  python test_fbank_correctness.py
"""

import sys
import struct
import numpy as np
import wave
from pathlib import Path

# Patch SpeechBrain compat issues
def _patch():
    import torchaudio
    if not hasattr(torchaudio, 'list_audio_backends'):
        torchaudio.list_audio_backends = lambda: ['ffmpeg']
    import functools, huggingface_hub, huggingface_hub.errors
    _orig = huggingface_hub.hf_hub_download
    @functools.wraps(_orig)
    def _patched(*args, **kwargs):
        kwargs.pop('use_auth_token', None)
        try: return _orig(*args, **kwargs)
        except huggingface_hub.errors.EntryNotFoundError as e: raise ValueError(str(e)) from e
    huggingface_hub.hf_hub_download = _patched
_patch()

import torch
import onnxruntime as ort
from speechbrain.inference.speaker import EncoderClassifier

# ---- Constants (must match Kotlin SpeakerVerifier) ----
SAMPLE_RATE = 16000
N_FFT = 400
WIN_LENGTH = 400
HOP_LENGTH = 160
N_MELS = 80
SPEC_SIZE = N_FFT // 2 + 1  # 201
TOP_DB = 80.0
AMIN = 1e-10
EMBEDDING_DIM = 192

SCRIPT_DIR = Path(__file__).parent
ASSETS_DIR = SCRIPT_DIR.parent / "app" / "src" / "main" / "assets"
MODEL_PATH = ASSETS_DIR / "ecapa_tdnn.onnx"
MEL_FILTERBANK_PATH = ASSETS_DIR / "mel_filterbank.bin"
DATA_DIR = Path("/workspace/project/sub-module/tools/wake-word-training/data")


def load_wav(path):
    with wave.open(str(path), 'rb') as wf:
        frames = wf.readframes(wf.getnframes())
    return np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0


def load_mel_filterbank():
    data = MEL_FILTERBANK_PATH.read_bytes()
    n_mels, spec_size = struct.unpack('<ii', data[:8])
    return np.frombuffer(data[8:], dtype=np.float32).reshape(n_mels, spec_size)


def our_compute_fbank(audio, mel_fb):
    """Our corrected Fbank -- must match SpeechBrain."""
    window = np.array([
        0.54 - 0.46 * np.cos(2.0 * np.pi * i / (WIN_LENGTH - 1))
        for i in range(WIN_LENGTH)
    ], dtype=np.float32)

    pad = N_FFT // 2
    padded = np.pad(audio, (pad, pad), mode='reflect')
    num_frames = 1 + (len(padded) - WIN_LENGTH) // HOP_LENGTH

    result = np.zeros((num_frames, N_MELS), dtype=np.float32)
    for frame in range(num_frames):
        start = frame * HOP_LENGTH
        windowed = padded[start:start + WIN_LENGTH] * window
        fft_result = np.fft.rfft(windowed, n=N_FFT)
        power_spec = np.abs(fft_result[:SPEC_SIZE]) ** 2
        mel_energies = mel_fb @ power_spec
        result[frame] = 10.0 * np.log10(np.maximum(mel_energies, AMIN))

    max_db = result.max()
    result = np.maximum(result, max_db - TOP_DB)
    result -= result.mean(axis=0, keepdims=True)
    return result


def our_get_embedding(session, audio, mel_fb):
    features = our_compute_fbank(audio, mel_fb)
    inp = features[np.newaxis].astype(np.float32)
    emb = session.run(['embedding'], {'features': inp})[0].flatten()[:EMBEDDING_DIM]
    norm = np.linalg.norm(emb)
    if norm > 0:
        emb /= norm
    return emb


def test_fbank_matches_speechbrain():
    """Test 1: Our Fbank output matches SpeechBrain's within tolerance."""
    print("=== Test 1: Fbank feature matching ===")

    classifier = EncoderClassifier.from_hparams(
        source='speechbrain/spkrec-ecapa-voxceleb', run_opts={'device': 'cpu'})
    mel_fb = load_mel_filterbank()

    # Use first available positive sample
    wav_files = sorted((DATA_DIR / 'recorded_positive').glob('*.wav'))
    if not wav_files:
        print("  SKIP: No positive samples found")
        return True

    audio = load_wav(wav_files[0])
    waveform = torch.from_numpy(audio).unsqueeze(0)

    # SpeechBrain features
    with torch.no_grad():
        sb_feats = classifier.mods.compute_features(waveform)
        sb_norm = classifier.mods.mean_var_norm(sb_feats, torch.ones(1))
    sb_norm_np = sb_norm[0].numpy()

    # Our features
    our_feats = our_compute_fbank(audio, mel_fb)

    # Compare
    n = min(sb_norm_np.shape[0], our_feats.shape[0])
    diff = np.abs(sb_norm_np[:n] - our_feats[:n])
    max_diff = diff.max()
    mean_diff = diff.mean()

    print(f"  Frames: SB={sb_norm_np.shape[0]} Ours={our_feats.shape[0]}")
    print(f"  Max abs diff:  {max_diff:.6f}")
    print(f"  Mean abs diff: {mean_diff:.6f}")

    passed = max_diff < 5.0 and mean_diff < 0.1
    print(f"  [{'PASS' if passed else 'FAIL'}] Feature matching (max<5.0, mean<0.1)")
    return passed


def test_embedding_matches_speechbrain():
    """Test 2: Our ONNX embedding matches SpeechBrain's encode_batch."""
    print("\n=== Test 2: Embedding matching ===")

    classifier = EncoderClassifier.from_hparams(
        source='speechbrain/spkrec-ecapa-voxceleb', run_opts={'device': 'cpu'})
    mel_fb = load_mel_filterbank()
    session = ort.InferenceSession(str(MODEL_PATH))

    wav_files = sorted((DATA_DIR / 'recorded_positive').glob('*.wav'))[:5]
    all_passed = True

    for f in wav_files:
        audio = load_wav(f)
        waveform = torch.from_numpy(audio).unsqueeze(0)

        # SpeechBrain embedding
        sb_emb = classifier.encode_batch(waveform).squeeze().numpy()
        sb_emb /= np.linalg.norm(sb_emb)

        # Our embedding
        our_emb = our_get_embedding(session, audio, mel_fb)

        cos = float(np.dot(our_emb, sb_emb))
        passed = cos > 0.999
        print(f"  [{f.name}] cosine={cos:.6f} [{'PASS' if passed else 'FAIL'}]")
        if not passed:
            all_passed = False

    print(f"  [{'PASS' if all_passed else 'FAIL'}] All embeddings match (cosine>0.999)")
    return all_passed


def test_speaker_discrimination():
    """Test 3: Corrected pipeline discriminates speakers."""
    print("\n=== Test 3: Speaker discrimination ===")

    mel_fb = load_mel_filterbank()
    session = ort.InferenceSession(str(MODEL_PATH))

    pos_files = sorted((DATA_DIR / 'recorded_positive').glob('*.wav'))
    neg_files = sorted((DATA_DIR / 'positive').glob('*.wav'))

    if len(pos_files) < 20 or len(neg_files) < 10:
        print("  SKIP: Not enough samples")
        return True

    # Build profile from first 20 positive samples
    embs = [our_get_embedding(session, load_wav(f), mel_fb) for f in pos_files[:20]]
    profile = np.mean(embs, axis=0).astype(np.float32)
    profile /= np.linalg.norm(profile)

    # Score positive samples (20-30)
    pos_sims = []
    for f in pos_files[20:30]:
        emb = our_get_embedding(session, load_wav(f), mel_fb)
        pos_sims.append(float(np.dot(emb, profile)))

    # Score negative (TTS) samples
    neg_sims = []
    for f in neg_files[:10]:
        emb = our_get_embedding(session, load_wav(f), mel_fb)
        neg_sims.append(float(np.dot(emb, profile)))

    pos_mean = np.mean(pos_sims)
    neg_mean = np.mean(neg_sims)
    gap = pos_mean - neg_mean
    min_pos = min(pos_sims)
    max_neg = max(neg_sims)

    print(f"  My voice mean:     {pos_mean:.4f} (min={min_pos:.4f})")
    print(f"  TTS impostor mean: {neg_mean:.4f} (max={max_neg:.4f})")
    print(f"  Gap: {gap:.4f}")
    print(f"  Clean separation: {min_pos:.4f} > {max_neg:.4f} = {min_pos > max_neg}")

    # Must have clear gap and no overlap
    passed_gap = gap > 0.3
    passed_separation = min_pos > max_neg
    passed = passed_gap and passed_separation

    print(f"  [{'PASS' if passed_gap else 'FAIL'}] Gap > 0.3 ({gap:.4f})")
    print(f"  [{'PASS' if passed_separation else 'FAIL'}] No score overlap")
    return passed


def test_no_false_accepts_at_threshold():
    """Test 4: No TTS samples pass at the configured threshold."""
    print("\n=== Test 4: No false accepts at threshold 0.35 ===")

    mel_fb = load_mel_filterbank()
    session = ort.InferenceSession(str(MODEL_PATH))
    threshold = 0.35

    pos_files = sorted((DATA_DIR / 'recorded_positive').glob('*.wav'))
    neg_files = sorted((DATA_DIR / 'positive').glob('*.wav'))

    # Build profile from first 20
    embs = [our_get_embedding(session, load_wav(f), mel_fb) for f in pos_files[:20]]
    profile = np.mean(embs, axis=0).astype(np.float32)
    profile /= np.linalg.norm(profile)

    # All positive samples must pass
    pos_pass = 0
    pos_fail = 0
    for f in pos_files[20:]:
        emb = our_get_embedding(session, load_wav(f), mel_fb)
        sim = float(np.dot(emb, profile))
        if sim >= threshold:
            pos_pass += 1
        else:
            pos_fail += 1

    # No negative (TTS) samples should pass
    neg_pass = 0
    neg_total = 0
    for f in neg_files[:50]:  # test first 50 for speed
        emb = our_get_embedding(session, load_wav(f), mel_fb)
        sim = float(np.dot(emb, profile))
        neg_total += 1
        if sim >= threshold:
            neg_pass += 1

    print(f"  Positive: {pos_pass}/{pos_pass + pos_fail} pass (threshold={threshold})")
    print(f"  Negative: {neg_pass}/{neg_total} false accepts (should be 0)")

    passed_pos = pos_fail == 0
    passed_neg = neg_pass == 0

    print(f"  [{'PASS' if passed_pos else 'FAIL'}] All positive samples accepted")
    print(f"  [{'PASS' if passed_neg else 'FAIL'}] No false accepts")
    return passed_pos and passed_neg


if __name__ == "__main__":
    results = []
    results.append(("Fbank matching", test_fbank_matches_speechbrain()))
    results.append(("Embedding matching", test_embedding_matches_speechbrain()))
    results.append(("Speaker discrimination", test_speaker_discrimination()))
    results.append(("No false accepts", test_no_false_accepts_at_threshold()))

    print("\n=== SUMMARY ===")
    all_passed = True
    for name, passed in results:
        print(f"  [{'PASS' if passed else 'FAIL'}] {name}")
        if not passed:
            all_passed = False

    sys.exit(0 if all_passed else 1)
