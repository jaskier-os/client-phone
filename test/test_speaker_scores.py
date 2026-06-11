"""
Speaker verification score analysis.

Runs the ECAPA-TDNN model against all available voice data to analyze
score distributions and identify false acceptance risk.

Tests against:
  1. recorded_positive/ -- user's real voice (should score high)
  2. recorded_negative/ -- same user, non-wake-word (same speaker baseline)
  3. positive/          -- TTS-generated wake word, different voice (impostor)
  4. adversarial/       -- TTS adversarial samples, different voice (impostor)
  5. impostor_tts.wav   -- Piper TTS synthetic voice (should score low)

Usage:
  python test_speaker_scores.py                     # analyze with current profile
  python test_speaker_scores.py --regenerate        # regenerate profile from recorded_positive, then analyze
  python test_speaker_scores.py --threshold 0.60    # test with custom threshold
"""

import sys
import struct
import argparse
import numpy as np
import onnxruntime as ort
from pathlib import Path

# ---- Constants (must match Kotlin SpeakerVerifier) ----
SAMPLE_RATE = 16000
N_FFT = 400
N_MELS = 80
WIN_LENGTH = 400
HOP_LENGTH = 160
SPEC_SIZE = N_FFT // 2 + 1  # 201
TOP_DB = 80.0
AMIN = 1e-10
EMBEDDING_DIM = 192
DEFAULT_THRESHOLD = 0.30  # matches Kotlin AppConfig.SPEAKER_SIMILARITY_THRESHOLD

SCRIPT_DIR = Path(__file__).parent
ASSETS_DIR = SCRIPT_DIR.parent / "app" / "src" / "main" / "assets"
MODEL_PATH = ASSETS_DIR / "ecapa_tdnn.onnx"
PROFILE_PATH = ASSETS_DIR / "voice_profile.bin"
MEL_FILTERBANK_PATH = ASSETS_DIR / "mel_filterbank.bin"

WAKE_WORD_DATA = Path("/workspace/project/sub-module/tools/wake-word-training/data")
RECORDED_POSITIVE_DIR = WAKE_WORD_DATA / "recorded_positive"
RECORDED_NEGATIVE_DIR = WAKE_WORD_DATA / "recorded_negative"
TTS_POSITIVE_DIR = WAKE_WORD_DATA / "positive"
ADVERSARIAL_DIR = WAKE_WORD_DATA / "adversarial"
IMPOSTOR_TTS_PATH = SCRIPT_DIR / "impostor_tts.wav"


def load_wav_16k_mono(path: str) -> np.ndarray:
    """Load WAV file as float32 mono 16kHz. Resamples if needed."""
    p = str(path)
    if p.lower().endswith('.mp3'):
        from pydub import AudioSegment
        audio = AudioSegment.from_file(p)
        audio = audio.set_channels(1).set_frame_rate(SAMPLE_RATE).set_sample_width(2)
        return np.array(audio.get_array_of_samples(), dtype=np.float32) / 32768.0

    import wave
    with wave.open(p, 'rb') as wf:
        channels = wf.getnchannels()
        rate = wf.getframerate()
        width = wf.getsampwidth()
        assert width == 2, f"Expected 16-bit, got {width*8}-bit"
        frames = wf.readframes(wf.getnframes())

    samples = np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0

    # Mix to mono if stereo
    if channels > 1:
        samples = samples.reshape(-1, channels).mean(axis=1)

    # Resample if not 16kHz
    if rate != SAMPLE_RATE:
        from scipy.signal import resample_poly
        from math import gcd
        g = gcd(SAMPLE_RATE, rate)
        samples = resample_poly(samples, SAMPLE_RATE // g, rate // g).astype(np.float32)

    return samples


def load_mel_filterbank() -> np.ndarray:
    """Load pre-extracted SpeechBrain mel filterbank from binary asset.
    Format: int32 n_mels, int32 spec_size, then n_mels * spec_size float32 (row-major).
    """
    data = MEL_FILTERBANK_PATH.read_bytes()
    n_mels, spec_size = struct.unpack('<ii', data[:8])
    fb = np.frombuffer(data[8:], dtype=np.float32).reshape(n_mels, spec_size)
    return fb


def compute_fbank(audio: np.ndarray, mel_filterbank: np.ndarray) -> np.ndarray:
    """Compute log-mel filterbank features matching SpeechBrain's pipeline exactly.

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

    input_tensor = features[np.newaxis, :, :].astype(np.float32)
    outputs = session.run(["embedding"], {"features": input_tensor})
    raw_embedding = outputs[0].flatten()[:EMBEDDING_DIM]

    norm = np.linalg.norm(raw_embedding)
    if norm > 0:
        raw_embedding = raw_embedding / norm

    return raw_embedding


def load_profile(path: Path) -> np.ndarray:
    """Load profile from binary file."""
    data = path.read_bytes()
    count = len(data) // 4
    return np.array(struct.unpack(f'<{count}f', data), dtype=np.float32)


def save_profile(embedding: np.ndarray, path: Path):
    """Save embedding as little-endian float32 binary."""
    with open(path, 'wb') as f:
        for val in embedding:
            f.write(struct.pack('<f', float(val)))
    print(f"  Saved profile to {path} ({len(embedding)} floats, {len(embedding)*4} bytes)")


def regenerate_profile(session, mel_fb):
    """Regenerate voice_profile.bin from all recorded_positive samples."""
    print("\n=== Regenerating profile from recorded_positive ===")

    wav_files = sorted(RECORDED_POSITIVE_DIR.glob("*.wav"))
    if not wav_files:
        print("  ERROR: No WAV files in recorded_positive/")
        return None

    print(f"  Found {len(wav_files)} recordings")

    embeddings = []
    skipped = 0
    for f in wav_files:
        try:
            audio = load_wav_16k_mono(str(f))
            if len(audio) < WIN_LENGTH:
                skipped += 1
                continue
            emb = get_embedding(session, audio, mel_fb)
            embeddings.append(emb)
        except Exception as e:
            print(f"  WARNING: Failed on {f.name}: {e}")
            skipped += 1

    print(f"  Computed {len(embeddings)} embeddings (skipped {skipped})")

    if not embeddings:
        print("  ERROR: No valid embeddings computed")
        return None

    avg_embedding = np.mean(embeddings, axis=0).astype(np.float32)
    norm = np.linalg.norm(avg_embedding)
    if norm > 0:
        avg_embedding = avg_embedding / norm

    # Compute intra-speaker variance
    sims = [float(np.dot(emb, avg_embedding)) for emb in embeddings]
    print(f"  Intra-speaker similarity: min={min(sims):.4f} max={max(sims):.4f} "
          f"mean={np.mean(sims):.4f} std={np.std(sims):.4f}")

    save_profile(avg_embedding, PROFILE_PATH)
    return avg_embedding


def score_directory(session, mel_fb, profile, directory, label):
    """Score all WAV files in a directory against the profile."""
    wav_files = sorted(directory.glob("*.wav"))
    if not wav_files:
        print(f"\n  [{label}] No WAV files found in {directory}")
        return []

    scores = []
    errors = 0
    for f in wav_files:
        try:
            audio = load_wav_16k_mono(str(f))
            if len(audio) < WIN_LENGTH:
                errors += 1
                continue
            emb = get_embedding(session, audio, mel_fb)
            sim = float(np.dot(emb, profile))
            scores.append((f.name, sim))
        except Exception as e:
            errors += 1

    return scores


def print_distribution(scores, label, threshold):
    """Print score distribution statistics."""
    if not scores:
        print(f"\n  [{label}] No scores to analyze")
        return

    sims = [s[1] for s in scores]
    above = sum(1 for s in sims if s >= threshold)
    below = len(sims) - above

    print(f"\n  [{label}] {len(scores)} files analyzed")
    print(f"    Min:    {min(sims):.4f}")
    print(f"    Max:    {max(sims):.4f}")
    print(f"    Mean:   {np.mean(sims):.4f}")
    print(f"    Std:    {np.std(sims):.4f}")
    print(f"    Median: {np.median(sims):.4f}")
    print(f"    Above threshold ({threshold}): {above}/{len(scores)}")
    print(f"    Below threshold ({threshold}): {below}/{len(scores)}")

    # Show worst cases
    sorted_scores = sorted(scores, key=lambda x: x[1])
    if label.startswith("POSITIVE"):
        # For positives, worst = lowest scores (false rejects)
        print(f"    Lowest 5 (false reject risk):")
        for name, sim in sorted_scores[:5]:
            status = "OK" if sim >= threshold else "FAIL"
            print(f"      [{status}] {name}: {sim:.4f}")
    else:
        # For negatives, worst = highest scores (false accept risk)
        print(f"    Highest 5 (false accept risk):")
        for name, sim in sorted_scores[-5:]:
            status = "FAIL" if sim >= threshold else "OK"
            print(f"      [{status}] {name}: {sim:.4f}")


def find_optimal_threshold(pos_scores, neg_scores):
    """Find optimal threshold that maximizes separation."""
    if not pos_scores or not neg_scores:
        return None

    pos_sims = sorted([s[1] for s in pos_scores])
    neg_sims = sorted([s[1] for s in neg_scores])

    min_pos = min(pos_sims)
    max_neg = max(neg_sims)

    print(f"\n  === Threshold Analysis ===")
    print(f"  Lowest positive score:  {min_pos:.4f}")
    print(f"  Highest negative score: {max_neg:.4f}")
    print(f"  Gap: {min_pos - max_neg:.4f}")

    if min_pos > max_neg:
        optimal = (min_pos + max_neg) / 2
        print(f"  Clean separation! Optimal threshold: {optimal:.4f}")
        # Also show EER-style analysis
        for t in [0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75]:
            fr = sum(1 for s in pos_sims if s < t) / len(pos_sims) * 100
            fa = sum(1 for s in neg_sims if s >= t) / len(neg_sims) * 100
            marker = " <-- current" if abs(t - DEFAULT_THRESHOLD) < 0.01 else ""
            print(f"    t={t:.2f}: FRR={fr:.1f}% FAR={fa:.1f}%{marker}")
        return optimal
    else:
        print(f"  WARNING: No clean separation -- scores overlap!")
        print(f"  Overlap range: [{max_neg:.4f}, {min_pos:.4f}]")
        # Find threshold that minimizes total error
        best_t = 0.5
        best_err = float('inf')
        for t_int in range(30, 80):
            t = t_int / 100.0
            fr = sum(1 for s in pos_sims if s < t)
            fa = sum(1 for s in neg_sims if s >= t)
            total = fr + fa
            if total < best_err:
                best_err = total
                best_t = t
        print(f"  Best threshold (min total errors): {best_t:.2f} ({best_err} errors)")
        for t in [0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75]:
            fr = sum(1 for s in pos_sims if s < t) / len(pos_sims) * 100
            fa = sum(1 for s in neg_sims if s >= t) / len(neg_sims) * 100
            marker = " <-- current" if abs(t - DEFAULT_THRESHOLD) < 0.01 else ""
            print(f"    t={t:.2f}: FRR={fr:.1f}% FAR={fa:.1f}%{marker}")
        return best_t


def main():
    parser = argparse.ArgumentParser(description="Speaker verification score analysis")
    parser.add_argument("--regenerate", action="store_true",
                        help="Regenerate profile from recorded_positive before testing")
    parser.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD,
                        help=f"Similarity threshold (default: {DEFAULT_THRESHOLD})")
    args = parser.parse_args()

    threshold = args.threshold

    print(f"=== Speaker Verification Score Analysis ===")
    print(f"Model:     {MODEL_PATH}")
    print(f"Profile:   {PROFILE_PATH}")
    print(f"Threshold: {threshold}")

    if not MODEL_PATH.exists():
        print(f"\nERROR: Model not found at {MODEL_PATH}")
        sys.exit(1)

    if not MEL_FILTERBANK_PATH.exists():
        print(f"\nERROR: Mel filterbank not found at {MEL_FILTERBANK_PATH}")
        sys.exit(1)

    session = ort.InferenceSession(str(MODEL_PATH))
    mel_fb = load_mel_filterbank()

    if args.regenerate:
        profile = regenerate_profile(session, mel_fb)
        if profile is None:
            sys.exit(1)
    else:
        if not PROFILE_PATH.exists():
            print(f"\nERROR: Profile not found at {PROFILE_PATH}")
            print("Run with --regenerate to create from recorded_positive/")
            sys.exit(1)
        profile = load_profile(PROFILE_PATH)

    print(f"\nProfile: {len(profile)} dims, norm={np.linalg.norm(profile):.4f}")

    # === SAME SPEAKER (should be HIGH) ===
    print("\n--- Scoring recorded_positive / my voice, wake word (should be HIGH) ---")
    pos_scores = score_directory(session, mel_fb, profile, RECORDED_POSITIVE_DIR, "POSITIVE")
    print_distribution(pos_scores, "POSITIVE recorded_positive (my voice, wake word)", threshold)

    print("\n--- Scoring recorded_negative / my voice, non-wake-word (should be HIGH) ---")
    my_neg_scores = score_directory(session, mel_fb, profile, RECORDED_NEGATIVE_DIR, "POSITIVE")
    print_distribution(my_neg_scores, "POSITIVE recorded_negative (my voice, other words)", threshold)

    # === DIFFERENT SPEAKER / TTS (should be LOW) ===
    print("\n--- Scoring positive/ / TTS wake word, different voice (should be LOW) ---")
    tts_pos_scores = score_directory(session, mel_fb, profile, TTS_POSITIVE_DIR, "NEGATIVE")
    print_distribution(tts_pos_scores, "NEGATIVE TTS positive (different voice, wake word)", threshold)

    print("\n--- Scoring adversarial/ / TTS adversarial, different voice (should be LOW) ---")
    adv_scores = score_directory(session, mel_fb, profile, ADVERSARIAL_DIR, "NEGATIVE")
    print_distribution(adv_scores, "NEGATIVE TTS adversarial (different voice)", threshold)

    # Piper TTS single impostor
    tts_single = []
    if IMPOSTOR_TTS_PATH.exists():
        print("\n--- Scoring Piper TTS impostor (single sample) ---")
        try:
            audio = load_wav_16k_mono(str(IMPOSTOR_TTS_PATH))
            emb = get_embedding(session, audio, mel_fb)
            sim = float(np.dot(emb, profile))
            tts_single = [("impostor_tts.wav", sim)]
            status = "FAIL" if sim >= threshold else "OK"
            print(f"  [{status}] impostor_tts.wav: similarity={sim:.4f} "
                  f"(threshold={threshold}, {len(audio)/SAMPLE_RATE:.1f}s)")
        except Exception as e:
            print(f"  ERROR: {e}")
    else:
        print(f"\n  SKIP: {IMPOSTOR_TTS_PATH} not found")

    # Combined analysis
    all_my_voice = pos_scores + my_neg_scores
    all_impostor = tts_pos_scores + adv_scores + tts_single
    optimal = find_optimal_threshold(all_my_voice, all_impostor)

    # Final summary
    my_pass = sum(1 for _, s in all_my_voice if s >= threshold)
    impostor_fail = sum(1 for _, s in all_impostor if s >= threshold)
    print(f"\n=== SUMMARY (threshold={threshold}) ===")
    print(f"  My voice accepts:      {my_pass}/{len(all_my_voice)} samples pass")
    print(f"  Impostor false accepts: {impostor_fail}/{len(all_impostor)} impostor samples pass (should be 0)")
    if optimal and optimal != threshold:
        print(f"  Suggested threshold: {optimal:.2f}")

    sys.exit(0 if impostor_fail == 0 else 1)


if __name__ == "__main__":
    main()
