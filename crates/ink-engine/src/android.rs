use super::*;
use jni::{JNIEnv, objects::{JObject,JString,JFloatArray,JIntArray,JByteArray,JByteBuffer}, sys::{jlong,jint,jfloat,jboolean,jstring,jbyteArray,jintArray}};
use std::collections::HashMap;
use std::sync::{Mutex,OnceLock};
use std::sync::atomic::{AtomicI64,Ordering};

static ENGINES:OnceLock<Mutex<HashMap<i64,Engine>>>=OnceLock::new();
static CLIPBOARD:OnceLock<Mutex<Option<selection::Clipboard>>>=OnceLock::new();
fn clipboard()->&'static Mutex<Option<selection::Clipboard>>{CLIPBOARD.get_or_init(||Mutex::new(None))}
static NEXT:AtomicI64=AtomicI64::new(1);
fn engines()->&'static Mutex<HashMap<i64,Engine>> {ENGINES.get_or_init(||Mutex::new(HashMap::new()))}
fn output(env:&JNIEnv,value:Value)->jstring {env.new_string(value.to_string()).map(|s|s.into_raw()).unwrap_or(std::ptr::null_mut())}
fn error(message:impl Into<String>)->Value {json!({"error":message.into()})}

#[no_mangle]
pub extern "system" fn Java_art_inkstone_NativeBridge_create(_env:JNIEnv,_this:JObject,width:jint,height:jint)->jlong {
    match Engine::new(width.max(0) as usize,height.max(0) as usize) {
        Ok(engine)=>{let id=NEXT.fetch_add(1,Ordering::Relaxed);engines().lock().unwrap().insert(id,engine);id},Err(_)=>0
    }
}
#[no_mangle]
pub extern "system" fn Java_art_inkstone_NativeBridge_destroy(_env:JNIEnv,_this:JObject,id:jlong) {engines().lock().unwrap().remove(&id);}
#[no_mangle]
pub extern "system" fn Java_art_inkstone_NativeBridge_command(mut env:JNIEnv,_this:JObject,id:jlong,input:JString)->jstring {
    let text:String=match env.get_string(&input) {Ok(s)=>s.into(),Err(_)=>return output(&env,error("Invalid command"))};
    let result=match serde_json::from_str::<Value>(&text) {
        Ok(v)=> match engines().lock().unwrap().get_mut(&id) {
            Some(e)=>{
                let op=v["op"].as_str().unwrap_or("");
                if op=="selectionPaste"{if let Some(copy)=clipboard().lock().unwrap().clone(){e.clipboard=Some(copy);}}
                let result=e.command(&v);
                if result.is_ok() && matches!(op,"selectionCopy"|"selectionCut"|"selectionCopyPaste"){*clipboard().lock().unwrap()=e.clipboard.clone();}
                result.unwrap_or_else(error)
            },None=>error("Drawing engine is closed")
        },Err(_)=>error("Invalid command JSON")
    };
    output(&env,result)
}
#[no_mangle]
pub extern "system" fn Java_art_inkstone_NativeBridge_stroke(env:JNIEnv,_this:JObject,id:jlong,phase:jint,input:JFloatArray,
    kind:jint,color:jint,size:jfloat,opacity:jfloat,flow:jfloat,stabilization:jfloat,pressure_size:jfloat,pressure_opacity:jfloat,fade_length:jfloat,sample_stride:jint) {
    let len=env.get_array_length(&input).unwrap_or(0) as usize;
    let stride=sample_stride as usize;
    if !matches!(stride,4|5) || len>65536 || len%stride!=0 {return;}
    let mut values=vec![0f32;len];if env.get_float_array_region(&input,0,&mut values).is_err(){return;}
    let mut guard=engines().lock().unwrap();let Some(e)=guard.get_mut(&id) else{return;};
    if phase==3 {e.cancel();return;}
    let brush=Brush {kind:kind as u8,color:color as u32,size,opacity,flow,stabilization,pressure_size,pressure_opacity,fade_length};
    if phase!=0 && !values.is_empty(){e.update_brush(brush);}
    for (i,p) in values.chunks_exact(stride).enumerate() {
        let sample=Sample{x:p[0],y:p[1],pressure:p[2],tilt:p[3]};
        if phase==0 && i==0 {e.begin(sample,brush);if stride==5{e.set_sample_time(p[4]);}}
        else if stride==5{e.sample_timed(sample,p[4]);}else{e.sample(sample);}
    }
    e.flush_gpu();
    if phase==2 {e.end();}
}
#[no_mangle]
pub extern "system" fn Java_art_inkstone_NativeBridge_render(env:JNIEnv,_this:JObject,id:jlong,buffer:JByteBuffer,background:jboolean)->jboolean {
    let Ok(capacity)=env.get_direct_buffer_capacity(&buffer) else{return 0;};
    let Ok(ptr)=env.get_direct_buffer_address(&buffer) else{return 0;};
    let mut guard=engines().lock().unwrap();let Some(e)=guard.get_mut(&id) else{return 0;};
    let required=e.width*e.height*4;if capacity<required {return 0;}
    // JNI owns the direct buffer for the entire synchronous call; capacity was checked above.
    let bytes=unsafe {std::slice::from_raw_parts_mut(ptr,required)};
    e.render(bytes,background!=0) as jboolean
}
#[no_mangle]
pub extern "system" fn Java_art_inkstone_NativeBridge_save(env:JNIEnv,_this:JObject,id:jlong)->jbyteArray {
    let mut guard=engines().lock().unwrap();let Some(e)=guard.get_mut(&id) else{return std::ptr::null_mut();};
    match e.save() {Ok(bytes)=>env.byte_array_from_slice(&bytes).map(|a|a.into_raw()).unwrap_or(std::ptr::null_mut()),Err(_)=>std::ptr::null_mut()}
}
#[no_mangle]
pub extern "system" fn Java_art_inkstone_NativeBridge_load(env:JNIEnv,_this:JObject,id:jlong,bytes:JByteArray)->jstring {
    if env.get_array_length(&bytes).unwrap_or(i32::MAX)>128*1024*1024 {return output(&env,error("Document is too large"));}
    let result=match env.convert_byte_array(&bytes) {
        Ok(bytes)=>match Engine::load(&bytes) {
            Ok(mut e)=>{let mut guard=engines().lock().unwrap();if let Some(slot)=guard.get_mut(&id){
                e.set_renderer(&slot.renderer_requested.clone()).ok();let info=e.info();*slot=e;info
            }else{error("Engine is closed")}},
            Err(e)=>error(e)
        },Err(_)=>error("Could not read document")
    };output(&env,result)
}
#[no_mangle]
pub extern "system" fn Java_art_inkstone_NativeBridge_importPixels(env:JNIEnv,_this:JObject,id:jlong,width:jint,height:jint,input:JIntArray)->jstring {
    let len=env.get_array_length(&input).unwrap_or(0) as usize;
    if len>MAX_PIXELS {return output(&env,error("Image is too large"));}
    let mut pixels=vec![0i32;len];if env.get_int_array_region(&input,0,&mut pixels).is_err(){return output(&env,error("Could not read image"));}
    let pixels:Vec<u32>=pixels.into_iter().map(|p|p as u32).collect();
    let result=match engines().lock().unwrap().get_mut(&id) {
        Some(e)=>match e.import(width.max(0) as usize,height.max(0) as usize,&pixels) {Ok(())=>e.info(),Err(s)=>error(s)},None=>error("Engine is closed")
    };output(&env,result)
}

#[no_mangle]
pub extern "system" fn Java_art_inkstone_NativeBridge_thumbnails(env:JNIEnv,_this:JObject,id:jlong,size:jint)->jintArray {
    let size=size.clamp(16,128) as usize;
    let guard=engines().lock().unwrap();let Some(e)=guard.get(&id) else{return std::ptr::null_mut();};
    let mut pixels=Vec::with_capacity(e.state.layers.len()*size*size);
    for l in &e.state.layers {for y in 0..size {for x in 0..size {
        let sx=x*e.width/size;let sy=y*e.height/size;
        pixels.push(l.masked_pixel(sy*e.width+sx) as i32);
    }}}
    let Ok(array)=env.new_int_array(pixels.len() as i32) else{return std::ptr::null_mut();};
    if env.set_int_array_region(&array,0,&pixels).is_err(){return std::ptr::null_mut();}array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_art_inkstone_NativeBridge_renderPatch(env:JNIEnv,_this:JObject,id:jlong,force:jboolean)->jintArray {
    let mut guard=engines().lock().unwrap();
    let Some(e)=guard.get_mut(&id) else{return std::ptr::null_mut();};
    if force!=0{e.exterior_dirty=true;}
    let interior=e.render_patch(force!=0);let exterior=e.exterior_patch();
    if interior.is_none() && exterior.is_none(){return std::ptr::null_mut();}
    let mut patch=interior.unwrap_or_else(||vec![0,0,0,0]);
    if let Some(exterior)=exterior{patch.extend(exterior);}
    let Ok(array)=env.new_int_array(patch.len() as i32) else { e.dirty=true;e.exterior_dirty=true;return std::ptr::null_mut(); };
    if env.set_int_array_region(&array,0,&patch).is_err(){e.dirty=true;e.exterior_dirty=true;return std::ptr::null_mut();}
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_art_inkstone_NativeBridge_preview(env:JNIEnv,_this:JObject,id:jlong)->jintArray {
    let guard=engines().lock().unwrap();let Some(e)=guard.get(&id) else{return std::ptr::null_mut();};
    let scale=(480.0/e.width.max(e.height) as f32).min(1.0);
    let w=(e.width as f32*scale).max(1.0) as usize;let h=(e.height as f32*scale).max(1.0) as usize;
    let mut pixels=Vec::with_capacity(2+w*h);pixels.extend([w as i32,h as i32]);
    for y in 0..h {for x in 0..w {pixels.push(e.color_at(x*e.width/w,y*e.height/h,true) as i32);}}
    let Ok(array)=env.new_int_array(pixels.len() as i32) else{return std::ptr::null_mut();};
    if env.set_int_array_region(&array,0,&pixels).is_err(){return std::ptr::null_mut();}array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_art_inkstone_NativeBridge_writePsd(mut env:JNIEnv,_this:JObject,id:jlong,path:JString)->jstring {
    let path:String=match env.get_string(&path){Ok(s)=>s.into(),Err(_)=>return output(&env,error("Invalid export path"))};
    let result=match engines().lock().unwrap().get_mut(&id) {
        Some(e)=>std::fs::File::create(&path).and_then(|file|e.write_psd(&mut std::io::BufWriter::new(file))).map(|_|json!({"ok":true})).unwrap_or_else(|e|error(e.to_string())),
        None=>error("Drawing engine is closed")
    };output(&env,result)
}

#[no_mangle]
pub extern "system" fn Java_art_inkstone_NativeBridge_transformSnapshot(env:JNIEnv,_this:JObject,id:jlong)->jintArray {
    let mut guard=engines().lock().unwrap();let Some(e)=guard.get_mut(&id)else{return std::ptr::null_mut();};
    let data=e.transform_snapshot();let Ok(array)=env.new_int_array(data.len() as i32)else{return std::ptr::null_mut();};
    if env.set_int_array_region(&array,0,&data).is_err(){return std::ptr::null_mut();}array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_art_inkstone_NativeBridge_importPsd(mut env:JNIEnv,_this:JObject,id:jlong,path:JString)->jstring{
    let path:String=match env.get_string(&path){Ok(v)=>v.into(),Err(_)=>return output(&env,error("Invalid PSD path"))};
    let result=(||->Result<Value,String>{
        let file=std::fs::File::open(path).map_err(|e|e.to_string())?;
        if file.metadata().map_err(|e|e.to_string())?.len()>128*1024*1024{return Err("PSD files must be smaller than 128 MiB".into());}
        let mut bytes=vec![];use std::io::Read;file.take(128*1024*1024+1).read_to_end(&mut bytes).map_err(|e|e.to_string())?;
        let (mut next,warnings)=Engine::read_psd(&bytes)?;let mut guard=engines().lock().unwrap();let slot=guard.get_mut(&id).ok_or("Drawing engine is closed")?;
        next.set_renderer(&slot.renderer_requested.clone()).ok();let mut info=next.info();info["importWarnings"]=json!(warnings);*slot=next;Ok(info)
    })().unwrap_or_else(error);output(&env,result)
}
