struct Params {
    canvas: vec4<u32>,
    rect: vec4<u32>,
    dab: vec4<f32>,
    flags: vec4<u32>,
    region: vec4<u32>,
    palette:array<vec4<u32>,3>,
    color_effect:vec4<f32>,
    direction:vec4<f32>,
    wet:vec4<f32>,
    contact:array<vec4<u32>,21>,
}
// The 9 × 9 wet contact field, bilinear in straight 8-bit channels like ContactField::at.
fn contact_color(i:u32)->vec4<f32>{return unpack(p.contact[i/4u][i%4u]);}
fn contact_at(q:vec2<f32>)->u32{
 let v=clamp((q+1.0)*4.0,vec2(0.0),vec2(8.0));let ix=u32(floor(v.x));let iy=u32(floor(v.y));let f=v-floor(v);
 let ix1=min(ix+1u,8u);let iy1=min(iy+1u,8u);
 let a=contact_color(iy*9u+ix);let b=contact_color(iy*9u+ix1);let c=contact_color(iy1*9u+ix);let d=contact_color(iy1*9u+ix1);
 let rgb=mix(mix(a.rgb,b.rgb,f.x),mix(c.rgb,d.rgb,f.x),f.y);
 return pack(vec4(rgb,a.w));
}
@group(0) @binding(0) var<storage, read_write> pixels: array<u32>;
@group(0) @binding(1) var<storage, read> selection: array<u32>;
@group(0) @binding(2) var<uniform> p: Params;
@group(0) @binding(3) var<storage, read_write> tip: array<u32>;
@group(0) @binding(4) var<storage, read_write> output_pixels: array<u32>;
fn unpack(c:u32)->vec4<f32>{return vec4<f32>(f32((c>>16u)&255u),f32((c>>8u)&255u),f32(c&255u),f32(c>>24u))/255.0;}
fn strand_color(base:u32,delta:vec2<f32>,radius:f32)->vec4<f32>{
 let effect=p.color_effect;
 if effect.x==0.0{return unpack(base);}
 let lateral=dot(delta,vec2(-p.direction.y,p.direction.x))/radius;
 var across=0.0;
 if effect.y==1.0{across=lateral*0.5;}
 if effect.y==2.0{across=floor((lateral+1.0)*8.0)/16.0;}
 let t=fract(effect.z+across);
 if effect.x==2.0 && effect.w>=2.0{
  let pos=t*effect.w;let ai=u32(pos)%u32(effect.w);let bi=(ai+1u)%u32(effect.w);
  let a=unpack(p.palette[ai/4u][ai%4u]);let b=unpack(p.palette[bi/4u][bi%4u]);
  return vec4(mix(a.rgb,b.rgb,fract(pos)),unpack(base).a);
 }
 if effect.x==2.0{return unpack(base);}
 let h=t*6.0;let x=1.0-abs(h%2.0-1.0);var rgb=vec3(1.0,0.0,x);
 switch u32(h){case 0u:{rgb=vec3(1.0,x,0.0);}case 1u:{rgb=vec3(x,1.0,0.0);}case 2u:{rgb=vec3(0.0,1.0,x);}case 3u:{rgb=vec3(0.0,x,1.0);}case 4u:{rgb=vec3(x,0.0,1.0);}default:{}}
 return vec4(rgb,unpack(base).a);
}
fn pack(c:vec4<f32>)->u32{let v=vec4<u32>(clamp(c,vec4(0.0),vec4(1.0))*255.0+0.5);return(v.w<<24u)|(v.x<<16u)|(v.y<<8u)|v.z;}
fn mix_rgba(a:u32,b:u32,t:f32)->u32{
 let av=unpack(a);let bv=unpack(b);let aa=av.w*(1.0-t);let ba=bv.w*t;let alpha=aa+ba;
 if(alpha<0.00001){return 0u;}return pack(vec4((av.xyz*aa+bv.xyz*ba)/alpha,alpha));
}
fn over(dst:u32,src:u32,opacity:f32)->u32{
 let d=unpack(dst);let s=unpack(src);let sa=s.w*opacity;if(sa<=0.0){return dst;}if(sa>=1.0){return src;}
 let a=sa+d.w*(1.0-sa);return pack(vec4((s.xyz*sa+d.xyz*d.w*(1.0-sa))/max(a,0.0001),a));
}
fn noise(x:u32,y:u32,seed:u32)->f32{
 var n=x*374761393u+y*668265263u+seed;n=(n^(n>>13u))*1274126177u;return f32((n^(n>>16u))&65535u)/65535.0;
}
fn ramp(n:f32,a:f32,b:f32)->f32{return clamp((n-a)/(b-a),0.0,1.0);}
fn paper_grain(pos:vec2<f32>,scale:f32,seed:u32)->f32 {
 let q=pos/scale;let cell=vec2<i32>(floor(q));let f=fract(q);let u=f*f*(vec2(3.0)-2.0*f);
 let a=noise(bitcast<u32>(cell.x),bitcast<u32>(cell.y),seed);let b=noise(bitcast<u32>(cell.x+1),bitcast<u32>(cell.y),seed);
 let c=noise(bitcast<u32>(cell.x),bitcast<u32>(cell.y+1),seed);let d=noise(bitcast<u32>(cell.x+1),bitcast<u32>(cell.y+1),seed);
 return mix(mix(a,b,u.x),mix(c,d,u.x),u.y);
}
fn material_texture(kind:u32,pos:vec2<f32>,tip:vec2<f32>,radius:f32,d:f32)->f32 {
 let fine=paper_grain(pos,1.6,119u);
 switch kind {
 case 4u:{let paper=paper_grain(pos,5.0,211u);let body=max(1.0-d*d,0.0);let rim=clamp((d-0.64)/0.25,0.0,1.0)*max(1.0-d,0.0);return (body*0.065+rim*0.14)*(0.3+paper*0.9+fine*0.15);}
 case 11u,42u:{let q=tip.y/max(radius*0.6,0.1);let edge=clamp(1.0-q*q*q*q,0.0,1.0);let bristle=paper_grain(vec2(tip.x,tip.y*0.08),1.8,57u);var gaps=0.08+bristle*0.25;if kind==42u{gaps=max(0.16+(bristle-0.43)*0.9,0.0)*ramp(bristle,0.40,0.46);}return edge*gaps*(0.4+fine*0.6);}
 case 32u:{let warp=sin(tip.x*0.72);let weft=sin(tip.y*0.66);return 0.14+0.045*warp*weft;}
 case 33u:{let tooth=paper_grain(pos,3.0,47u);return (0.12+fine*0.2)*(0.5+tooth)*ramp(tooth,0.20,0.55);}
 case 34u:{let cells=paper_grain(pos,8.0,89u);return (0.09+0.18*fine)*ramp(abs(cells-0.5),0.02,0.16);}
 case 35u:{let holes=paper_grain(pos,4.0,173u);return 0.32*max(holes-0.45,0.0)*max(1.0-d*d,0.0)*ramp(holes,0.52,0.58);}
 case 36u:{let patches=paper_grain(pos,7.0,257u);return patches*0.23*ramp(patches,0.25,0.65)*(0.6+fine*0.4);}
 case 37u:{let cell=pos/vec2(13.0,17.0);let seed=noise(u32(floor(cell.x)),u32(floor(cell.y)),317u);let uv=fract(cell)*2.0-vec2(1.0)+vec2((seed-0.5)*0.45,(noise(u32(floor(cell.x)),u32(floor(cell.y)),733u)-0.5)*0.4);let sign=select(1.0,-1.0,seed<0.6);let a=(uv.x+sign*uv.y)*0.70710677;let b=(-sign*uv.x+uv.y)*0.70710677;let leaf=a*a/0.25+abs(b)/0.85;return select(0.0,(1.0-ramp(leaf,0.88,1.0))*(0.18+0.26*max(1.0-leaf,0.0))*ramp(abs(a),0.025,0.065),seed>0.26);}
 case 38u:{let a=abs(sin(pos.x*0.72+pos.y*0.48));let b=abs(sin(pos.x*0.38-pos.y*0.76));return 0.37*(1.0-ramp(a,0.16,0.22)*ramp(b,0.09,0.15));}
 case 39u:{let q=abs(fract(pos/3.0))-vec2(0.5);return 0.35*(1.0-ramp(length(q),0.28,0.32));}
 case 40u:{let wear=paper_grain(pos,4.0,421u);return (0.14+fine*0.2)*ramp(wear,0.16,0.55);}
 case 41u:{let paper=paper_grain(pos,2.8,509u);return (0.19+fine*0.24)*max(1.0-d*d*d*d*d*d,0.0)*ramp(paper,0.10,0.45);}
 default:{return 1.0;}
 }
}
fn sample_tip(pos:vec2<f32>)->u32{
 let v=clamp(pos,vec2(0.0),vec2(31.0));let q=vec2<u32>(v);let q1=min(q+vec2(1u),vec2(31u));let f=fract(v);
 return mix_rgba(mix_rgba(tip[q.y*32u+q.x],tip[q.y*32u+q1.x],f.x),mix_rgba(tip[q1.y*32u+q.x],tip[q1.y*32u+q1.x],f.x),f.y);
}
@compute @workgroup_size(8,8) fn pickup(@builtin(global_invocation_id) id:vec3<u32>){
 if(any(id.xy>=vec2(32u))){return;}let s=vec2<i32>(floor(p.dab.xy+(vec2<f32>(id.xy)+0.5-16.0)/16.0*p.dab.z));
 var c=0u;if(all(s>=vec2(0)) && all(s<vec2<i32>(p.canvas.xy))){c=pixels[u32(s.y)*p.canvas.x+u32(s.x)];}
 let i=id.y*32u+id.x;tip[1024u+i]=c;if(p.flags.w!=0u){tip[i]=c;}
}
@compute @workgroup_size(8,8) fn advance(@builtin(global_invocation_id) id:vec3<u32>){
 if(any(id.xy>=vec2(32u))){return;}let i=id.y*32u+id.x;tip[i]=mix_rgba(tip[i],tip[1024u+i],0.08);
}
@compute @workgroup_size(8,8) fn paint(@builtin(global_invocation_id) id:vec3<u32>){
 let xy=id.xy+p.rect.xy;if(any(xy>=p.rect.zw)){return;}let i=xy.y*p.canvas.x+xy.x;
 if(p.flags.z!=0u && selection[i]==0u){return;}
 let delta=vec2<f32>(xy)+0.5-p.dab.xy;let radius=p.dab.z;let d=length(delta)/max(radius,0.1);
 if(d>1.0+0.7/radius){return;}let edge=clamp((1.0-d)*radius+0.5,0.0,1.0);
 let smudging=p.canvas.z==6u || p.canvas.z>=128u;var shape=p.canvas.z;if(smudging){shape=select(2u,p.canvas.z&127u,p.canvas.z>=128u);}
 if(p.canvas.z>=64u && p.canvas.z<=127u){shape=p.canvas.z-64u;}
 var texture=1.0;
 let tip=select(delta,vec2(dot(delta,vec2(-p.direction.y,p.direction.x)),dot(delta,p.direction.xy)),(p.color_effect.x>0.0 && p.color_effect.y>0.0) || shape==11u || shape==42u);
 switch(shape){
 case 1u:{texture=0.16+noise(xy.x,xy.y,91u)*0.58;}
 case 2u:{texture=max(exp(-4.0*d*d)-exp(-4.0),0.0)/(1.0-exp(-4.0))*0.12;}
 case 8u,9u:{let hard=select(0.35,0.7,shape==9u);let t=clamp((1.0-d)/(1.0-hard),0.0,1.0);texture=t*t*(3.0-2.0*t)*0.18;}
 case 10u:{texture=select(0.0,0.65*max(1.0-d,0.0),noise(xy.x,xy.y,p.flags.y)>0.965);}

 case 12u:{texture=select(0.0,0.2,abs(tip.y)<radius*0.48);}
 case 13u:{texture=pow(noise(xy.x,xy.y,19u),2.0)*0.38;}
 case 14u:{texture=(0.1+noise(xy.x,xy.y,73u)*0.3)*max(1.0-d*d,0.0);}
 case 3u:{texture=pow(noise(xy.x,xy.y,p.flags.y/15u),1.8)*0.7;}
 case 4u,11u,32u,33u,34u,35u,36u,37u,38u,39u,40u,41u,42u:{texture=material_texture(shape,vec2<f32>(xy),tip,radius,d);}
 case 7u:{texture=select(0.0,0.85,abs(select(delta.x,tip.y,p.color_effect.x>0.0 && p.color_effect.y>0.0))<radius*0.32);}
 default:{}
 }
 if(shape>=16u && shape<=31u){let hard=f32(shape-16u)/15.0*0.95;let t=clamp((1.0-d)/(1.0-hard),0.0,1.0);texture=t*t*(3.0-2.0*t);}
 if p.color_effect.x>0.0 && p.color_effect.y==2.0{let lateral=dot(delta,vec2(-p.direction.y,p.direction.x))/radius;texture*=0.52+0.48*abs(sin(lateral*37.0));}
 var ink=pack(strand_color(p.flags.x,delta,radius));
 if p.wet.x!=0.0{ink=contact_at(delta/max(radius,0.1));}
 var hardness=1.0;
 if(p.canvas.z>=64u && p.canvas.z<=127u && (p.flags.x&255u)<255u){let hard=f32(p.flags.x&255u)/255.0*0.95;let t=clamp((1.0-d)/(1.0-hard),0.0,1.0);hardness=t*t*(3.0-2.0*t);}
 var coverage=1.0;if(p.flags.z!=0u){coverage=f32(selection[i])/255.0;}
 let wet_edge=1.0-p.wet.y*pow(clamp(d,0.0,1.0),2.0);
 let ink_coverage=clamp(p.dab.w*edge*texture*wet_edge*hardness,0.0,1.0);
 var normalized=1.0-pow(1.0-ink_coverage,p.direction.z);
 if(ink_coverage<=0.2){let r=p.direction.z;let a=ink_coverage;normalized=r*a*(1.0+(1.0-r)*a*0.5+(1.0-r)*(2.0-r)*a*a/6.0);}
 let alpha=normalized*coverage;let dst=pixels[i];let lock=p.canvas.w!=0u;
 if(smudging){
  let carried=sample_tip((delta/radius+1.0)*16.0-0.5);var mask=edge;
  switch(shape){
  case 2u,4u:{mask=pow(max(1.0-d*d,0.0),2.0);}
  case 8u,9u:{let hard=select(0.35,0.7,shape==9u);let t=clamp((1.0-d)/(1.0-hard),0.0,1.0);mask=t*t*(3.0-2.0*t);}
  case 7u,10u,11u,12u,13u,14u,32u,33u,34u,35u,36u,37u,38u,39u,40u,41u,42u:{mask=min(texture,1.0)*edge;}
  default:{}
  }
  let c=mix_rgba(dst,carried,clamp(p.dab.w*mask*0.32*coverage,0.0,1.0));pixels[i]=select(c,(c&0xffffffu)|(dst&0xff000000u),lock);
 }else if(p.canvas.z==5u || (p.canvas.z>=16u && p.canvas.z<=31u) || (p.canvas.z>=64u && p.canvas.z<=127u)){if(!lock){pixels[i]=(dst&0xffffffu)|(u32(f32(dst>>24u)*(1.0-alpha)+0.5)<<24u);}}
 else if(lock){if(dst>>24u!=0u){pixels[i]=(over(dst|0xff000000u,ink|0xff000000u,alpha)&0xffffffu)|(dst&0xff000000u);}}
 else{pixels[i]=over(dst,ink,alpha);}
}
@compute @workgroup_size(8,8) fn gather(@builtin(global_invocation_id) id:vec3<u32>){
 if(any(id.xy>=p.region.zw)){return;}let xy=p.region.xy+id.xy;output_pixels[id.y*p.region.z+id.x]=pixels[xy.y*p.canvas.x+xy.x];
}
