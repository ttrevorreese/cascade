"""Generates Cascade's built-in alarm tones into app/src/main/res/raw (SPEC M6).

Synthesised rather than downloaded, so there is nothing to license. Each tone is one loop: the
player repeats it seamlessly, so every file starts and ends at silence. Mono, 16-bit, 44.1 kHz WAV
(WAV rather than Ogg: Vorbis encoder padding can leave a gap at the loop point).

    python scripts/make_tones.py

The keys here must match data/sound/Tones.kt. "classic" (tone_classic.wav) predates this script
and is not regenerated.
"""
import pathlib
import wave

import numpy as np

RATE = 44100
OUT = pathlib.Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res" / "raw"
PEAK = 10 ** (-1 / 20)  # -1 dBFS


def t(seconds):
    return np.arange(int(seconds * RATE)) / RATE


def silence(seconds):
    return np.zeros(int(seconds * RATE))


def envelope(n, attack, release):
    """Linear attack and release in seconds, so no note starts or stops with a click."""
    env = np.ones(n)
    a, r = int(attack * RATE), int(release * RATE)
    if a:
        env[:a] = np.linspace(0, 1, a)
    if r:
        env[-r:] *= np.linspace(1, 0, r)
    return env


def place(track, sound, at):
    start = int(at * RATE)
    end = min(len(track), start + len(sound))
    track[start:end] += sound[: end - start]


def bell(freq, seconds, decay):
    """Inharmonic partials with exponential decay: a struck bell or chime."""
    x = t(seconds)
    partials = [(1.0, 1.0), (2.76, 0.45), (5.4, 0.25), (8.93, 0.12)]
    tone = sum(amp * np.sin(2 * np.pi * freq * ratio * x) * np.exp(-x * decay * ratio ** 0.5) for ratio, amp in partials)
    return tone * envelope(len(x), 0.004, 0.05)


def mallet(freq, seconds):
    """Marimba-like: a fundamental and a 4x overtone that dies fast."""
    x = t(seconds)
    tone = np.sin(2 * np.pi * freq * x) * np.exp(-x * 7) + 0.35 * np.sin(2 * np.pi * freq * 4 * x) * np.exp(-x * 30)
    return tone * envelope(len(x), 0.002, 0.03)


def beep(freq, seconds, shape="sine"):
    x = t(seconds)
    if shape == "square":
        # Band-limited square: odd harmonics up to ~8 kHz, so it is bright without aliasing.
        tone = sum(np.sin(2 * np.pi * freq * k * x) / k for k in range(1, int(8000 / freq) + 1, 2))
    else:
        tone = np.sin(2 * np.pi * freq * x)
    return tone * envelope(len(x), 0.005, 0.01)


def dawn():
    """Gentle: a soft major chord that swells and fades, 4 s."""
    x = t(4.0)
    chord = sum(np.sin(2 * np.pi * f * x) * a for f, a in [(261.63, 1.0), (329.63, 0.7), (392.0, 0.6), (523.25, 0.3)])
    swell = np.sin(np.pi * x / 4.0) ** 2
    return chord * swell


def chimes():
    """C, E, G, C on bells, then a rest, 3.2 s."""
    track = silence(3.2)
    for i, f in enumerate([523.25, 659.25, 783.99, 1046.5]):
        place(track, bell(f, 1.6, 2.5), i * 0.35)
    return track


def marimba():
    """A rising and falling figure, 2.4 s."""
    track = silence(2.4)
    for i, f in enumerate([392.0, 523.25, 659.25, 783.99, 659.25, 523.25]):
        place(track, mallet(f, 0.6), i * 0.2)
    return track


def pulse():
    """Four soft beeps and a pause, 2 s."""
    track = silence(2.0)
    for i in range(4):
        place(track, beep(880, 0.12), i * 0.22)
    return track


def digital():
    """The bedside-clock pattern: four quick square beeps, twice a second."""
    track = silence(1.0)
    for i in range(4):
        place(track, beep(2048, 0.06, "square"), i * 0.1)
    return track


def rising():
    """A tone that sweeps up an octave, repeated, 1.5 s."""
    x = t(1.2)
    freq = 600 * 2 ** (x / 1.2)
    phase = 2 * np.pi * np.cumsum(freq) / RATE
    tone = (np.sin(phase) + 0.3 * np.sin(2 * phase)) * envelope(len(x), 0.01, 0.04)
    return np.concatenate([tone, silence(0.3)])


def klaxon():
    """Harsh: a buzzing sawtooth pair, on and off, 1.6 s."""
    def buzz(seconds):
        x = t(seconds)
        saw = sum(np.sin(2 * np.pi * 330 * k * x) / k for k in range(1, 24))
        saw += 0.8 * sum(np.sin(2 * np.pi * 349 * k * x) / k for k in range(1, 22))
        return saw * envelope(len(x), 0.005, 0.01)
    track = silence(1.6)
    place(track, buzz(0.5), 0.0)
    place(track, buzz(0.5), 0.8)
    return track


TONES = {
    "dawn": dawn,
    "chimes": chimes,
    "marimba": marimba,
    "pulse": pulse,
    "digital": digital,
    "rising": rising,
    "klaxon": klaxon,
}


def write(name, samples):
    samples = samples / np.max(np.abs(samples)) * PEAK
    # Start and end exactly at silence so the loop point never clicks.
    samples *= envelope(len(samples), 0.002, 0.002)
    pcm = (samples * 32767).astype("<i2")
    with wave.open(str(OUT / f"tone_{name}.wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())


if __name__ == "__main__":
    for key, make in TONES.items():
        write(key, make())
        print(f"tone_{key}.wav")
