"""
Speaker verification test suite.

Replicates the EXACT same Fbank pipeline as the Kotlin SpeakerVerifier,
matching SpeechBrain's spkrec-ecapa-voxceleb preprocessing:
  center-pad (reflect) -> framing (400 win, 160 hop) -> Hamming window ->
  FFT(400) -> power spectrum (201 bins) -> mel filterbank (80 bins, from .bin) ->
  10*log10 -> top_db clamp -> mean subtraction -> ONNX ECAPA-TDNN -> 192-dim embedding

Usage:
  python test_speaker_verification.py                    # run all tests
  python test_speaker_verification.py --generate-profile # generate voice_profile.bin from voice_profile.mp3
"""

import sys
import os
import struct
import argparse
import numpy as np
import onnxruntime as ort
from pathlib import Path

# ---- Constants (must match Kotlin SpeakerVerifier) ----
SAMPLE_RATE = 16000
N_FFT = 400
N_MELS = 80
WIN_LENGTH = 400     # 25ms
HOP_LENGTH = 160     # 10ms
SPEC_SIZE = N_FFT // 2 + 1  # 201
TOP_DB = 80.0
AMIN = 1e-10
EMBEDDING_DIM = 192
SIMILARITY_THRESHOLD = 0.30

SCRIPT_DIR = Path(__file__).parent
ASSETS_DIR = SCRIPT_DIR.parent / "app" / "src" / "main" / "assets"
MODEL_PATH = ASSETS_DIR / "ecapa_tdnn.onnx"
PROFILE_OUTPUT_PATH = ASSETS_DIR / "voice_profile.bin"
MEL_FILTERBANK_PATH = ASSETS_DIR / "mel_filterbank.bin"

VOICE_PROFILE_MP3 = SCRIPT_DIR / "voice_profile.mp3"
VOICE_SAMPLE_1 = SCRIPT_DIR / "voice_sample_1.mp3"
VOICE_SAMPLE_2 = SCRIPT_DIR / "voice_sample_2.mp3"


def load_audio_mono_16k(path: str) -> np.ndarray:
    """Load audio file, convert to mono float32 at 16kHz."""
    from pydub import AudioSegment

    audio = AudioSegment.from_file(path)
    audio = audio.set_channels(1).set_frame_rate(SAMPLE_RATE).set_sample_width(2)
    samples = np.array(audio.get_array_of_samples(), dtype=np.float32) / 32768.0
    return samples


def load_mel_filterbank() -> np.ndarray:
    """Load pre-extracted SpeechBrain mel filterbank from binary asset.
    Format: int32 n_mels, int32 spec_size, then n_mels * spec_size float32 (row-major).
    """
    data = MEL_FILTERBANK_PATH.read_bytes()
    n_mels, spec_size = struct.unpack('<ii', data[:8])
    return np.frombuffer(data[8:], dtype=np.float32).reshape(n_mels, spec_size)


def compute_fbank(audio: np.ndarray, mel_filterbank: np.ndarray) -> np.ndarray:
    """
    Compute log-mel filterbank features matching SpeechBrain's pipeline exactly.
    Returns [num_frames, N_MELS] array.

    Pipeline: center-pad (reflect) -> framing -> Hamming window -> FFT(400) ->
              power spectrum (201 bins) -> mel filterbank (80 bins) ->
              10*log10 -> top_db clamp -> per-utterance mean subtraction
    """
    if len(audio) < WIN_LENGTH:
        return np.zeros((0, N_MELS), dtype=np.float32)

    # Hamming window
    window = np.array([
        0.54 - 0.46 * np.cos(2.0 * np.pi * i / (WIN_LENGTH - 1))
        for i in range(WIN_LENGTH)
    ], dtype=np.float32)

    # Center-pad with reflection
    pad = N_FFT // 2
    padded = np.pad(audio, (pad, pad), mode='reflect')

    num_frames = 1 + (len(padded) - WIN_LENGTH) // HOP_LENGTH
    if num_frames <= 0:
        return np.zeros((0, N_MELS), dtype=np.float32)

    result = np.zeros((num_frames, N_MELS), dtype=np.float32)

    for frame in range(num_frames):
        start = frame * HOP_LENGTH
        windowed = padded[start:start + WIN_LENGTH] * window
        fft_result = np.fft.rfft(windowed, n=N_FFT)
        power_spec = np.abs(fft_result[:SPEC_SIZE]) ** 2
        mel_energies = mel_filterbank @ power_spec
        result[frame] = 10.0 * np.log10(np.maximum(mel_energies, AMIN))

    # top_db clamping
    max_db = result.max()
    result = np.maximum(result, max_db - TOP_DB)

    # Per-utterance mean subtraction (SpeechBrain InputNormalization, std_norm=False)
    result -= result.mean(axis=0, keepdims=True)

    return result


def get_embedding(session: ort.InferenceSession, audio: np.ndarray,
                  mel_filterbank: np.ndarray) -> np.ndarray:
    """Compute L2-normalized 192-dim embedding from audio."""
    features = compute_fbank(audio, mel_filterbank)
    if features.shape[0] == 0:
        raise ValueError("Audio too short for feature extraction")

    # [1, num_frames, 80]
    input_tensor = features[np.newaxis, :, :].astype(np.float32)

    outputs = session.run(["embedding"], {"features": input_tensor})
    raw_embedding = outputs[0].flatten()[:EMBEDDING_DIM]

    # L2 normalize
    norm = np.linalg.norm(raw_embedding)
    if norm > 0:
        raw_embedding = raw_embedding / norm

    return raw_embedding


def cosine_similarity(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.dot(a, b))


def save_profile(embedding: np.ndarray, path: Path):
    """Save embedding as little-endian float32 binary (matching Kotlin loadProfile)."""
    with open(path, 'wb') as f:
        for val in embedding:
            f.write(struct.pack('<f', float(val)))
    print(f"  Saved profile to {path} ({len(embedding)} floats, {len(embedding)*4} bytes)")


def load_profile(path: Path) -> np.ndarray:
    """Load profile from binary file."""
    data = path.read_bytes()
    count = len(data) // 4
    return np.array(struct.unpack(f'<{count}f', data), dtype=np.float32)


def generate_profile():
    """Generate voice_profile.bin from voice_profile.mp3."""
    print("=== Generating voice profile ===")
    print(f"  Source: {VOICE_PROFILE_MP3}")
    print(f"  Model:  {MODEL_PATH}")
    print(f"  Output: {PROFILE_OUTPUT_PATH}")

    audio = load_audio_mono_16k(str(VOICE_PROFILE_MP3))
    print(f"  Audio loaded: {len(audio)} samples ({len(audio)/SAMPLE_RATE:.1f}s)")

    session = ort.InferenceSession(str(MODEL_PATH))
    mel_fb = load_mel_filterbank()

    # Split into 3-second segments and average embeddings for robustness
    segment_len = SAMPLE_RATE * 3  # 3 seconds
    hop = SAMPLE_RATE * 2          # 2 second hop (1s overlap)
    embeddings = []

    pos = 0
    while pos + segment_len <= len(audio):
        segment = audio[pos:pos + segment_len]
        emb = get_embedding(session, segment, mel_fb)
        embeddings.append(emb)
        pos += hop

    # Also process full audio
    if len(audio) >= SAMPLE_RATE:  # at least 1 second
        emb = get_embedding(session, audio, mel_fb)
        embeddings.append(emb)

    print(f"  Computed {len(embeddings)} embeddings ({len(embeddings)-1} segments + 1 full)")

    # Average and L2-normalize
    avg_embedding = np.mean(embeddings, axis=0).astype(np.float32)
    norm = np.linalg.norm(avg_embedding)
    if norm > 0:
        avg_embedding = avg_embedding / norm

    print(f"  Profile embedding: norm={np.linalg.norm(avg_embedding):.4f} "
          f"first5={avg_embedding[:5]}")

    # Self-test: verify each segment against profile
    print("\n  Self-verification:")
    for i, emb in enumerate(embeddings):
        sim = cosine_similarity(emb, avg_embedding)
        label = "segment" if i < len(embeddings) - 1 else "full"
        status = "PASS" if sim >= SIMILARITY_THRESHOLD else "FAIL"
        print(f"    [{status}] {label} {i}: similarity={sim:.4f} (threshold={SIMILARITY_THRESHOLD})")

    save_profile(avg_embedding, PROFILE_OUTPUT_PATH)
    return avg_embedding


def run_tests():
    """Run verification tests against voice samples."""
    print("\n=== Running speaker verification tests ===")

    if not MODEL_PATH.exists():
        print(f"  SKIP: Model not found at {MODEL_PATH}")
        return False

    if not PROFILE_OUTPUT_PATH.exists():
        print(f"  SKIP: Profile not found at {PROFILE_OUTPUT_PATH}")
        print(f"  Run with --generate-profile first")
        return False

    session = ort.InferenceSession(str(MODEL_PATH))
    mel_fb = load_mel_filterbank()
    profile = load_profile(PROFILE_OUTPUT_PATH)

    print(f"  Profile loaded: {len(profile)} dims, norm={np.linalg.norm(profile):.4f}")
    print(f"  Profile first5: {profile[:5]}")

    all_passed = True
    results = []

    # Test 1: voice_sample_1.mp3 (should match - same speaker)
    if VOICE_SAMPLE_1.exists():
        audio = load_audio_mono_16k(str(VOICE_SAMPLE_1))
        emb = get_embedding(session, audio, mel_fb)
        sim = cosine_similarity(emb, profile)
        passed = sim >= SIMILARITY_THRESHOLD
        status = "PASS" if passed else "FAIL"
        print(f"\n  [{status}] voice_sample_1.mp3: similarity={sim:.4f} "
              f"(threshold={SIMILARITY_THRESHOLD}, {len(audio)/SAMPLE_RATE:.1f}s)")
        print(f"    Embedding first5: {emb[:5]}")
        results.append(("voice_sample_1.mp3", sim, passed))
        if not passed:
            all_passed = False
    else:
        print(f"\n  SKIP: {VOICE_SAMPLE_1} not found")

    # Test 2: voice_sample_2.mp3 (should match - same speaker)
    if VOICE_SAMPLE_2.exists():
        audio = load_audio_mono_16k(str(VOICE_SAMPLE_2))
        emb = get_embedding(session, audio, mel_fb)
        sim = cosine_similarity(emb, profile)
        passed = sim >= SIMILARITY_THRESHOLD
        status = "PASS" if passed else "FAIL"
        print(f"\n  [{status}] voice_sample_2.mp3: similarity={sim:.4f} "
              f"(threshold={SIMILARITY_THRESHOLD}, {len(audio)/SAMPLE_RATE:.1f}s)")
        print(f"    Embedding first5: {emb[:5]}")
        results.append(("voice_sample_2.mp3", sim, passed))
        if not passed:
            all_passed = False
    else:
        print(f"\n  SKIP: {VOICE_SAMPLE_2} not found")

    # Test 3: Synthetic noise (should NOT match)
    print("\n  --- Negative tests ---")
    np.random.seed(42)
    noise = np.random.randn(SAMPLE_RATE * 3).astype(np.float32) * 0.1
    emb_noise = get_embedding(session, noise, mel_fb)
    sim_noise = cosine_similarity(emb_noise, profile)
    passed_noise = sim_noise < SIMILARITY_THRESHOLD  # should be BELOW threshold
    status = "PASS" if passed_noise else "FAIL"
    print(f"  [{status}] random_noise: similarity={sim_noise:.4f} "
          f"(should be < {SIMILARITY_THRESHOLD})")
    results.append(("random_noise", sim_noise, passed_noise))
    if not passed_noise:
        all_passed = False

    # Test 4: Silence (should NOT match)
    silence = np.zeros(SAMPLE_RATE * 3, dtype=np.float32)
    emb_silence = get_embedding(session, silence, mel_fb)
    sim_silence = cosine_similarity(emb_silence, profile)
    passed_silence = sim_silence < SIMILARITY_THRESHOLD
    status = "PASS" if passed_silence else "FAIL"
    print(f"  [{status}] silence: similarity={sim_silence:.4f} "
          f"(should be < {SIMILARITY_THRESHOLD})")
    results.append(("silence", sim_silence, passed_silence))
    if not passed_silence:
        all_passed = False

    # Test 5: Cross-verify samples against each other (should be high similarity)
    if VOICE_SAMPLE_1.exists() and VOICE_SAMPLE_2.exists():
        audio1 = load_audio_mono_16k(str(VOICE_SAMPLE_1))
        audio2 = load_audio_mono_16k(str(VOICE_SAMPLE_2))
        emb1 = get_embedding(session, audio1, mel_fb)
        emb2 = get_embedding(session, audio2, mel_fb)
        cross_sim = cosine_similarity(emb1, emb2)
        passed_cross = cross_sim >= SIMILARITY_THRESHOLD
        status = "PASS" if passed_cross else "FAIL"
        print(f"\n  [{status}] cross_verify (sample1 vs sample2): similarity={cross_sim:.4f} "
              f"(threshold={SIMILARITY_THRESHOLD})")
        results.append(("cross_verify", cross_sim, passed_cross))
        if not passed_cross:
            all_passed = False

    # Summary
    print(f"\n=== Results: {'ALL PASSED' if all_passed else 'SOME FAILED'} ===")
    for name, sim, passed in results:
        print(f"  {'PASS' if passed else 'FAIL'} {name}: {sim:.4f}")

    return all_passed


def test_fbank_consistency():
    """Test that our Fbank produces consistent results."""
    print("\n=== Fbank consistency tests ===")

    mel_fb = load_mel_filterbank()
    print(f"  Mel filterbank shape: {mel_fb.shape}")
    print(f"  Mel filterbank sum per bin (first 5): {mel_fb.sum(axis=1)[:5]}")
    print(f"  Mel filterbank nonzero per bin (first 5): {(mel_fb > 0).sum(axis=1)[:5]}")

    # Test with a simple sine wave
    t = np.arange(SAMPLE_RATE * 2, dtype=np.float32) / SAMPLE_RATE
    sine_440 = (np.sin(2 * np.pi * 440 * t) * 0.5).astype(np.float32)

    features = compute_fbank(sine_440, mel_fb)
    print(f"\n  440Hz sine (2s): features shape={features.shape}")
    print(f"  Mean per mel bin (first 10): {features.mean(axis=0)[:10]}")

    # The 440Hz energy should be concentrated in lower-mid mel bins
    energy_per_bin = features.mean(axis=0)
    peak_bin = np.argmax(energy_per_bin)
    print(f"  Peak energy at mel bin {peak_bin} (expected ~20-30 for 440Hz)")

    # Verify deterministic
    features2 = compute_fbank(sine_440, mel_fb)
    assert np.allclose(features, features2), "Fbank not deterministic!"
    print("  Determinism check: PASS")

    return True


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Speaker verification test suite")
    parser.add_argument("--generate-profile", action="store_true",
                        help="Generate voice_profile.bin from voice_profile.mp3")
    parser.add_argument("--test-only", action="store_true",
                        help="Only run tests (skip profile generation)")
    args = parser.parse_args()

    print(f"FFT_SIZE={FFT_SIZE} (zero-padded from N_FFT={N_FFT})")
    print(f"specSize={FFT_SIZE // 2 + 1}")
    print(f"N_MELS={N_MELS}, WIN_LENGTH={WIN_LENGTH}, HOP_LENGTH={HOP_LENGTH}")
    print(f"Model: {MODEL_PATH}")
    print(f"Profile: {PROFILE_OUTPUT_PATH}")

    if args.test_only:
        test_fbank_consistency()
        success = run_tests()
        sys.exit(0 if success else 1)

    if args.generate_profile or not PROFILE_OUTPUT_PATH.exists():
        generate_profile()

    test_fbank_consistency()
    success = run_tests()
    sys.exit(0 if success else 1)
