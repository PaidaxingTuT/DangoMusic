# EchoMusic transition engine

The transition modules and JSON templates are copied from
https://github.com/hoowhoami/EchoMusic at 713e995bf349fb34da817d4e25bc4dcbd015aa12
(native/echo-audio-player/src/transition), under GPL-3.0.
Original source copyright and notices are preserved.

The only changes to the copied transition modules add serde serialization and
deserialization to TrackAnalysis, TransitionPlan and their enum fields so the
JNI bridge can exchange these values with the JVM. Analysis, cue decisions,
effect automation, DSP and the SoundTouch mixer remain the upstream code.

vendor/soundtouch-rs is the EchoMusic vendored implementation. See its
ECHOMUSIC_VENDOR.md and src/spectral/LICENSE-SIGNALSMITH.md for provenance.
Its development-only Cargo entries were removed for standalone builds.

src/lib.rs is the DangoMusic JNI adapter. It accepts decoded PCM from the
existing JVM decoders. No account credentials or playback URLs cross JNI.

## Verification

From PowerShell on Windows x64, with Cargo, Python and FFmpeg available:

    ./native/echo-transition/verification/verify.ps1 -Jdk21 'D:/.jdks/ms-21.0.9'

The JNI check exercises the four decision strategies, short-track and manual
rules, linear fade samples and distinct DJ template execution. The player
check supplies mock provider responses and a clocked audio sink. All audio
is synthesized locally, including different sample rates/channels and silent
head/tail regions. It does not contact music providers or open an audio device.

The original Rust tests can be run independently:

    cargo test --release --manifest-path native/echo-transition/Cargo.toml

Current bundled native target is Windows x64. Other builds show an unavailable
engine message. Audio decoding remains DangoMusic's existing JVM implementation.
