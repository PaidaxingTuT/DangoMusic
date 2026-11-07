"""Generate deterministic local audio for the player/JNI integration check."""
import math
import struct
import sys
import wave
from pathlib import Path

folder = Path(sys.argv[1])
folder.mkdir(parents=True, exist_ok=True)
for name, rate, channels, phase in [
    ("echo-a", 44100, 1, 0.2),
    ("echo-b", 48000, 2, 1.2),
]:
    with wave.open(str(folder / (name + ".wav")), "wb") as output:
        output.setparams((channels, 2, rate, 0, "NONE", ""))
        for second in range(40):
            data = bytearray()
            for sample_index in range(rate):
                time = second + sample_index / rate
                beat = int((time - phase) / 0.5)
                position = (time - phase) % 0.5
                amplitude = 0
                if phase <= time < 38 and position < 0.15:
                    amplitude = (17000 if beat % 4 == 0 else 11000) * math.exp(-position * 40)
                value = int(amplitude * math.sin(2 * math.pi * 140 * time))
                data.extend(struct.pack("<h", value) * channels)
            output.writeframesraw(data)
