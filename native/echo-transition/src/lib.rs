mod transition;

use jni::objects::{JFloatArray, JObject, JString};
use jni::sys::{jfloatArray, jint, jlong, jstring};
use jni::JNIEnv;
use serde::Deserialize;
use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{atomic::{AtomicI64, Ordering}, Mutex, OnceLock};
use transition::analysis::{TrackAnalysis, TrackAnalyzer};
use transition::decide::{TransitionPlan, TransitionRequest, TransitionTrigger, decide_transition};
use transition::mixer::{DeckInput, MixerConfig, TransitionMixer};
use transition::{TransitionMode, TransitionSettings};

static MIXERS: OnceLock<Mutex<HashMap<i64, TransitionMixer>>> = OnceLock::new();
static NEXT_ID: AtomicI64 = AtomicI64::new(1);
fn mixers() -> &'static Mutex<HashMap<i64, TransitionMixer>> {
    MIXERS.get_or_init(|| Mutex::new(HashMap::new()))
}
fn guarded<T: Default>(env: &mut JNIEnv, work: impl FnOnce(&mut JNIEnv) -> Result<T, String>) -> T {
    match catch_unwind(AssertUnwindSafe(|| work(env))) {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => { let _ = env.throw_new("java/lang/IllegalStateException", error); T::default() }
        Err(_) => { let _ = env.throw_new("java/lang/IllegalStateException", "Echo transition engine panicked"); T::default() }
    }
}
fn string(env: &mut JNIEnv, value: JString) -> Result<String, String> {
    env.get_string(&value).map(|text| text.into()).map_err(|e| e.to_string())
}
fn floats(env: &JNIEnv, array: &JFloatArray) -> Result<Vec<f32>, String> {
    let count = env.get_array_length(array).map_err(|e| e.to_string())?;
    let mut result = vec![0.0; count as usize];
    env.get_float_array_region(array, 0, &mut result).map_err(|e| e.to_string())?;
    Ok(result)
}
fn array(env: &JNIEnv, values: &[f32]) -> Result<jfloatArray, String> {
    let result = env.new_float_array(values.len() as i32).map_err(|e| e.to_string())?;
    env.set_float_array_region(&result, 0, values).map_err(|e| e.to_string())?;
    Ok(result.into_raw())
}
fn with_mixer<T>(id: i64, work: impl FnOnce(&mut TransitionMixer) -> T) -> Result<T, String> {
    let mut map = mixers().lock().map_err(|e| e.to_string())?;
    let mixer = map.get_mut(&id).ok_or("Echo transition mixer already closed")?;
    Ok(work(mixer))
}

#[derive(Deserialize)]
struct Request {
    a: TrackAnalysis,
    b: TrackAnalysis,
    mode: String,
    fade_secs: f32,
    match_tempo: bool,
    manual: bool,
    position_secs: f64,
}

#[no_mangle]
pub extern "system" fn Java_me_sorihiko_dango_music_EchoTransitionNative_analyze(
    mut env: JNIEnv, _: JObject, samples: JFloatArray, rate: jint,
    duration: f64, start: f64,
) -> jstring {
    guarded(&mut env, |env| {
        if rate <= 0 || !duration.is_finite() || !start.is_finite() { return Err("Invalid analysis format".into()); }
        let mono = floats(env, &samples)?;
        let mut analyzer = TrackAnalyzer::new(rate as u32, 1, duration, start);
        analyzer.push_mono(&mono);
        let result = serde_json::to_string(&analyzer.finish()).map_err(|e| e.to_string())?;
        env.new_string(result).map(|value| value.into_raw()).map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_me_sorihiko_dango_music_EchoTransitionNative_decide(
    mut env: JNIEnv, _: JObject, request: JString,
) -> jstring {
    guarded(&mut env, |env| {
        let request: Request = serde_json::from_str(&string(env, request)?).map_err(|e| e.to_string())?;
        let mode = TransitionMode::parse(&request.mode).ok_or("Unknown transition mode")?;
        let result = decide_transition(&TransitionRequest {
            settings: TransitionSettings { mode, fade_secs: request.fade_secs, match_tempo: request.match_tempo },
            trigger: if request.manual { TransitionTrigger::Manual } else { TransitionTrigger::EndOfTrack },
            a: &request.a, b: &request.b, a_position_secs: request.position_secs,
        });
        let json = result.map(|plan| serde_json::to_string(&plan)).transpose().map_err(|e| e.to_string())?.unwrap_or_default();
        env.new_string(json).map(|value| value.into_raw()).map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_me_sorihiko_dango_music_EchoTransitionNative_createMixer(
    mut env: JNIEnv, _: JObject, plan: JString, rate: jint, channels: jint, a_gain: f32, b_gain: f32,
) -> jlong {
    guarded(&mut env, |env| {
        if rate <= 0 || !(1..=2).contains(&channels) { return Err("Invalid mix format".into()); }
        let plan: TransitionPlan = serde_json::from_str(&string(env, plan)?).map_err(|e| e.to_string())?;
        if !plan.overlap_secs.is_finite() || !(0.0..=60.0).contains(&plan.overlap_secs) {
            return Err("Invalid overlap duration".into());
        }
        let mixer = TransitionMixer::new(MixerConfig {
            sample_rate: rate as u32, channels: channels as usize,
            overlap_frames: (plan.overlap_secs * rate as f64).round() as usize,
            plan: plan.template.map(|template| template.load()),
            linear_fade: plan.uses_plain_fade(),
            a_tempo_ratio: plan.a_tempo_ratio as f32,
            a_gain, b_gain,
            a_beat_secs: plan.a_beat_secs.map(|s| s as f32),
            b_beat_secs: plan.b_beat_secs.map(|s| s as f32),
        })?;
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        mixers().lock().map_err(|e| e.to_string())?.insert(id, mixer);
        Ok(id)
    })
}

#[no_mangle]
pub extern "system" fn Java_me_sorihiko_dango_music_EchoTransitionNative_push(
    mut env: JNIEnv, _: JObject, id: jlong, deck: jint, data: JFloatArray,
) {
    guarded(&mut env, |env| {
        let samples = if data.is_null() { None } else { Some(floats(env, &data)?) };
        with_mixer(id, |mixer| {
            let input = samples.as_ref().map_or(DeckInput::End, |values| DeckInput::Samples(values));
            if deck == 0 { mixer.push_a(input); } else { mixer.push_b(input); }
        })
    })
}

#[no_mangle]
pub extern "system" fn Java_me_sorihiko_dango_music_EchoTransitionNative_render(
    mut env: JNIEnv, _: JObject, id: jlong, frames: jint,
) -> jfloatArray {
    guarded(&mut env, |env| {
        if !(1..=65536).contains(&frames) { return Err("Invalid render size".into()); }
        let output = with_mixer(id, |mixer| mixer.render(frames as usize))?;
        array(env, &output.samples)
    })
}

#[no_mangle]
pub extern "system" fn Java_me_sorihiko_dango_music_EchoTransitionNative_info(
    mut env: JNIEnv, _: JObject, id: jlong,
) -> jlong {
    guarded(&mut env, |_| with_mixer(id, |mixer| {
        (mixer.is_finished() as i64)
            | ((mixer.a_finished() as i64) << 1)
            | ((mixer.a_queued_frames().min(65535) as i64) << 2)
            | ((mixer.b_queued_frames().min(65535) as i64) << 18)
            | ((mixer.a_preroll_frames_wanted().min(65535) as i64) << 34)
    }))
}

#[no_mangle]
pub extern "system" fn Java_me_sorihiko_dango_music_EchoTransitionNative_preroll(
    mut env: JNIEnv, _: JObject, id: jlong, data: JFloatArray,
) {
    guarded(&mut env, |env| {
        let samples = floats(env, &data)?;
        with_mixer(id, |mixer| mixer.preroll_a(&samples))
    })
}

#[no_mangle]
pub extern "system" fn Java_me_sorihiko_dango_music_EchoTransitionNative_remainder(
    mut env: JNIEnv, _: JObject, id: jlong,
) -> jfloatArray {
    guarded(&mut env, |env| {
        let samples = with_mixer(id, |mixer| mixer.take_b_remainder())?;
        array(env, &samples)
    })
}

#[no_mangle]
pub extern "system" fn Java_me_sorihiko_dango_music_EchoTransitionNative_release(
    mut env: JNIEnv, _: JObject, id: jlong,
) {
    guarded(&mut env, |_| {
        mixers().lock().map_err(|e| e.to_string())?.remove(&id);
        Ok(())
    })
}
