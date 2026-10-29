use super::*;

#[derive(Clone,Serialize,Deserialize)]
pub(super) struct LayerGroup {pub(super) id:u32,#[serde(default)] pub(super) parent_id:Option<u32>,#[serde(default)] pub(super) anchor_id:Option<u32>,pub(super) name:String,pub(super) visible:bool,pub(super) locked:bool,collapsed:bool,#[serde(default="group_opacity")] pub(super) opacity:f32,#[serde(default="group_blend")] pub(super) blend:String}
fn group_opacity()->f32{1.0}
fn group_blend()->String{"Pass Through".into()}
impl Layer {
    pub fn is_visible(&self)->bool{self.visible && self.group_visible}
    pub fn is_locked(&self)->bool{self.locked || self.group_locked}
}
impl Engine {
    pub(super) fn group_path(&self,id:Option<u32>)->Vec<&LayerGroup>{
        let mut path=Vec::new();let mut next=id;
        while let Some(g)=next.and_then(|id|self.state.groups.iter().find(|g|g.id==id)){
            if path.iter().any(|p:&&LayerGroup|p.id==g.id){break;}
            path.push(g);next=g.parent_id;
        }
        path.reverse();path
    }
    pub(super) fn in_group(&self,member:Option<u32>,id:u32)->bool{self.group_path(member).iter().any(|g|g.id==id)}
    pub(super) fn refresh_groups(&mut self){
        let flags:Vec<_>=self.state.layers.iter().map(|l|{let path=self.group_path(l.group_id);(!path.is_empty(),path.iter().all(|g|g.visible),path.iter().any(|g|g.locked))}).collect();
        for (l,(exists,visible,locked)) in self.state.layers.iter_mut().zip(flags){l.group_visible=visible;l.group_locked=locked;if !exists{l.group_id=None;}}
    }
    pub(super) fn normalize_groups(&mut self){
        fn scope(parent:Option<u32>,groups:&[LayerGroup],pending:&mut Vec<Layer>,out:&mut Vec<Layer>){
            while let Some(i)=pending.iter().position(|l|{
                let mut id=l.group_id;
                for _ in 0..=groups.len(){if id==parent{return true;}id=match id.and_then(|id|groups.iter().find(|g|g.id==id)){Some(g)=>g.parent_id,None=>return false};}false
            }){
                let mut id=pending[i].group_id;
                if id==parent{out.push(pending.remove(i));continue;}
                while let Some(g)=id.and_then(|id|groups.iter().find(|g|g.id==id)){if g.parent_id==parent{break;}id=g.parent_id;}
                scope(id,groups,pending,out);
            }
        }
        let active=self.state.layers[self.state.active].id;let mut pending=std::mem::take(&mut self.state.layers);
        scope(None,&self.state.groups,&mut pending,&mut self.state.layers);self.state.layers.extend(pending);
        self.state.active=self.state.layers.iter().position(|l|l.id==active).unwrap();
        let anchors:Vec<_>=self.state.groups.iter().map(|g|self.state.layers.iter().rposition(|l|self.in_group(l.group_id,g.id)).map(|i|self.state.layers.get(i+1).map(|l|l.id))).collect();
        for(g,anchor)in self.state.groups.iter_mut().zip(anchors){if let Some(anchor)=anchor{g.anchor_id=anchor;}}self.refresh_groups();
    }
    pub(super) fn composite_grouped<F:Fn(&Layer)->(u32,f32)>(&self,layers:&[Layer],c:u32,sample:F)->u32{
        self.composite_grouped_except(layers,c,None,sample)
    }
    pub(super) fn composite_grouped_except<F:Fn(&Layer)->(u32,f32)>(&self,layers:&[Layer],mut c:u32,exclude:Option<u32>,sample:F)->u32{
        let mut stack:[Option<&LayerGroup>;32]=[None;32];let mut backs=[0u32;32];let mut bases=[0.0;32];let mut depth=0;let mut base=0.0;
        let isolated=self.state.groups.iter().any(|g|g.blend!="Pass Through"||g.opacity<1.0);
        for l in layers{
            if isolated{
                let mut path:[Option<&LayerGroup>;32]=[None;32];let mut count=0;let mut next=l.group_id;
                while let Some(g)=next.and_then(|id|self.state.groups.iter().find(|g|g.id==id)){
                    if count==32{break;}next=g.parent_id;
                    if Some(g.id)!=exclude && (g.blend!="Pass Through"||g.opacity<1.0){path[count]=Some(g);count+=1;}
                }
                path[..count].reverse();let mut common=0;
                while common<depth && common<count && stack[common].unwrap().id==path[common].unwrap().id{common+=1;}
                while depth>common{depth-=1;let g=stack[depth].unwrap();c=composite(backs[depth],c,g.opacity,if g.blend=="Pass Through"{"Normal"}else{&g.blend});base=bases[depth];}
                for g in path.iter().take(count).skip(common){stack[depth]=*g;backs[depth]=c;bases[depth]=base;depth+=1;c=0;base=0.0;}
            }
            let(ink,coverage)=sample(l);
            if !l.clipping{base=if l.is_visible(){channel(ink,24)*coverage}else{0.0};}
            if l.is_visible(){c=composite(c,ink,l.opacity*coverage*if l.clipping{base}else{1.0},&l.blend);}
        }
        while depth>0{depth-=1;let g=stack[depth].unwrap();c=composite(backs[depth],c,g.opacity,if g.blend=="Pass Through"{"Normal"}else{&g.blend});}c
    }
    pub(super) fn move_layer(&mut self,v:&Value)->Result<(),String>{
        let id=v["id"].as_u64().ok_or("Missing layer")? as u32;
        let source=self.state.layers.iter().position(|l|l.id==id).ok_or("Layer not found")?;
        let group=v["groupId"].as_u64().map(|n|n as u32);
        if group.is_some() && !self.state.groups.iter().any(|g|Some(g.id)==group){return Err("Group not found".into());}
        let target=v["targetId"].as_u64().map(|n|n as u32);if target==Some(id) && self.state.layers[source].group_id==group{return Ok(());}
        let above=v["above"].as_bool().unwrap_or(true);let target_group=v["targetGroupId"].as_u64().map(|n|n as u32);
        self.structure(|e|{
            let active=e.state.layers[e.state.active].id;
            if let Some(group)=e.state.groups.iter_mut().find(|g|Some(g.id)==group){group.collapsed=false;}
            for g in &mut e.state.groups{if g.anchor_id==Some(id){g.anchor_id=e.state.layers.get(source+1).map(|l|l.id);}}
            let mut layer=e.state.layers.remove(source);layer.group_id=group;
            let position=if target==Some(id){source.min(e.state.layers.len())}else if let Some(index)=target.and_then(|id|e.state.layers.iter().position(|l|l.id==id)){
                let other=e.state.layers[index].group_id;
                if other!=group && other.is_some(){if above{e.state.layers.iter().rposition(|l|l.group_id==other).unwrap()+1}else{e.state.layers.iter().position(|l|l.group_id==other).unwrap()}}
                else{index+usize::from(above)}
            }else if let Some(gid)=target_group.or(group){
                let positions:Vec<_>=e.state.layers.iter().enumerate().filter(|(_,l)|e.in_group(l.group_id,gid)).map(|(i,_)|i).collect();
                if !positions.is_empty(){if target_group.is_none() || above{positions.last().unwrap()+1}else{positions[0]}}
                else{e.state.groups.iter().find(|g|g.id==gid).and_then(|g|g.anchor_id).and_then(|id|e.state.layers.iter().position(|l|l.id==id)).unwrap_or(e.state.layers.len())}
            }else{0};
            if let Some(g)=target_group.filter(|gid|Some(*gid)!=group).and_then(|gid|e.state.groups.iter_mut().find(|g|g.id==gid)){if above{g.anchor_id=Some(id);}}
            e.state.layers.insert(position,layer);e.state.active=e.state.layers.iter().position(|l|l.id==active).unwrap();e.state.layers[0].clipping=false;e.normalize_groups();
        });Ok(())
    }
    pub(super) fn group_command(&mut self,v:&Value)->Result<Value,String>{
        let action=v["action"].as_str().unwrap_or("create");
        if !matches!(action,"create"|"update"|"member"|"removeMember"|"ungroup"|"translate"|"move"){return Err("Unknown group action".into());}
        if action=="create" && self.state.groups.iter().any(|g|g.id>=u32::MAX-1){return Err("Group identifiers exhausted".into());}
        if action=="create" && self.state.groups.len()>=512{return Err("Group limit reached".into());}
        let id=v["id"].as_u64().map(|n|n as u32);
        let group=self.state.groups.iter().position(|g|Some(g.id)==id);
        if action!="create" && group.is_none(){return Err("Group not found".into());}
        if action=="translate" && self.group_path(id).iter().any(|g|g.locked){return Err("This group is locked".into());}
        if let Some(mode)=v["blend"].as_str(){if !matches!(mode,"Pass Through"|"Normal"|"Multiply"|"Screen"|"Overlay"|"Darken"|"Lighten"|"Color Burn"|"Linear Burn"|"Color Dodge"|"Add"|"Soft Light"|"Hard Light"|"Difference"|"Exclusion"|"Subtract"){return Err("Unknown group blend mode".into());}}
        let requested_index=v["index"].as_u64().unwrap_or(self.state.active as u64) as usize;
        if requested_index>=self.state.layers.len(){return Err("Layer not found".into());}
        let parent=if action=="create" && v.get("parentId").is_none() && !v["empty"].as_bool().unwrap_or(false){self.state.layers[requested_index].group_id}else{v["parentId"].as_u64().map(|n|n as u32)};
        if matches!(action,"move"|"create") && parent.is_some(){
            if !self.state.groups.iter().any(|g|Some(g.id)==parent){return Err("Parent group not found".into());}
            if self.group_path(parent).len()>=32{return Err("Group nesting limit reached".into());}
            if action=="move" && self.in_group(parent,id.unwrap()){return Err("A group cannot contain itself or its parent".into());}
        }
        if action=="create" && !v["empty"].as_bool().unwrap_or(false) && self.group_path(parent).len()>=32{return Err("Group nesting limit reached".into());}
        if action=="move"{
            let nested=self.state.groups.iter().filter(|g|self.in_group(Some(g.id),id.unwrap())).map(|g|self.group_path(Some(g.id)).len()-self.group_path(id).len()).max().unwrap_or(0);
            if self.group_path(parent).len()+nested+1>32{return Err("Group nesting limit reached".into());}
        }
        let index=v["index"].as_u64().unwrap_or(self.state.active as u64) as usize;
        if index>=self.state.layers.len(){return Err("Layer not found".into());}
        let translated=if action=="translate"{
            let dx=v["x"].as_i64().unwrap_or(0).clamp(-(self.width as i64),self.width as i64) as i32;
            let dy=v["y"].as_i64().unwrap_or(0).clamp(-(self.height as i64),self.height as i64) as i32;
            let members:Vec<_>=self.state.layers.iter().enumerate().filter(|(_,l)|self.in_group(l.group_id,id.unwrap()) && !l.is_locked()).collect();
            let extra:usize=members.iter().map(|(_,l)|self.width*self.height*4+l.exterior.len()*128*128*4).sum();
            if Self::layer_bytes(&self.state.layers)+extra>PIXEL_BUDGET*4{return Err("Not enough canvas memory to move this group".into());}
            let translated=members.into_iter().map(|(i,l)|Ok((i,l.remapped(self.width,self.height,&self.empty_pixels,|x,y|(x+dx,y+dy))?))).collect::<Result<Vec<_>,String>>()?;
            let tiles=self.state.layers.iter().enumerate().map(|(i,l)|translated.iter().find(|(at,_)|*at==i).map_or(l.exterior.len(),|(_,l)|l.exterior.len())).sum::<usize>();
            if tiles>1024{return Err("Movement exceeds off-canvas storage capacity".into());}translated
        }else{Vec::new()};
        self.structure(|e|{
            match action{
                "create"=>{let next=e.state.groups.iter().map(|g|g.id).max().unwrap_or(0).saturating_add(1);e.state.groups.push(LayerGroup{id:next,anchor_id:None,parent_id:parent,name:v["name"].as_str().unwrap_or("New group").chars().take(100).collect(),visible:true,locked:false,collapsed:false,opacity:1.0,blend:group_blend()});if !v["empty"].as_bool().unwrap_or(false){e.state.layers[index].group_id=Some(next);}},
                "member"=>{
                    let active_id=e.state.layers[e.state.active].id;
                    let mut layer=e.state.layers.remove(index);layer.group_id=id;
                    let at=e.state.layers.iter().rposition(|l|l.group_id==id).map_or(e.state.layers.len(),|n|n+1);e.state.layers.insert(at,layer);
                    e.state.active=e.state.layers.iter().position(|l|l.id==active_id).unwrap();
                },
                "removeMember"=>{e.state.layers[index].group_id=e.state.groups[group.unwrap()].parent_id;},
                "ungroup"=>{let parent=e.state.groups[group.unwrap()].parent_id;for l in &mut e.state.layers{if l.group_id==id{l.group_id=parent;}}for g in &mut e.state.groups{if g.parent_id==id{g.parent_id=parent;}}e.state.groups.remove(group.unwrap());},
                "move"=>{
                    let active=e.state.layers[e.state.active].id;
                    let members:Vec<_>=e.state.layers.iter().filter(|l|e.in_group(l.group_id,id.unwrap())).map(|l|l.id).collect();
                    for g in &mut e.state.groups{if g.anchor_id.is_some_and(|id|members.contains(&id)){let at=e.state.layers.iter().position(|l|Some(l.id)==g.anchor_id).unwrap();g.anchor_id=e.state.layers[at..].iter().find(|l|!members.contains(&l.id)).map(|l|l.id);}}
                    let mut moving=Vec::new();e.state.layers.retain(|l|{if members.contains(&l.id){moving.push(l.clone());false}else{true}});
                    e.state.groups[group.unwrap()].parent_id=parent;
                    if let Some(g)=e.state.groups.iter_mut().find(|g|Some(g.id)==parent){g.collapsed=false;}
                    let target_group=v["targetGroupId"].as_u64().map(|n|n as u32);
                    let target=v["targetId"].as_u64().map(|n|n as u32);let above=v["above"].as_bool().unwrap_or(true);
                    let candidates:Vec<_>=e.state.layers.iter().enumerate().filter(|(_,l)|target_group.map_or(Some(l.id)==target,|g|e.in_group(l.group_id,g))).map(|(i,_)|i).collect();
                    let empty_anchor=target_group.and_then(|id|e.state.groups.iter().find(|g|g.id==id)).and_then(|g|g.anchor_id).and_then(|id|e.state.layers.iter().position(|l|l.id==id));
                    let at=if !candidates.is_empty(){if above{candidates.last().unwrap()+1}else{candidates[0]}}else if let Some(at)=empty_anchor{at}else{e.state.layers.iter().rposition(|l|parent.is_some_and(|g|e.in_group(l.group_id,g))).map_or(e.state.layers.len(),|i|i+1)};
                    e.state.groups[group.unwrap()].anchor_id=e.state.layers.get(at).map(|l|l.id);
                    if above && !moving.is_empty(){if let Some(target)=target_group.filter(|target|Some(*target)!=parent).and_then(|target|e.state.groups.iter_mut().find(|g|g.id==target)){target.anchor_id=Some(moving[0].id);}}
                    if let Some(target_index)=target_group.filter(|target|Some(*target)!=parent).and_then(|id|e.state.groups.iter().position(|g|g.id==id)){
                        let source_index=e.state.groups.iter().position(|g|Some(g.id)==id).unwrap();
                        if source_index!=target_index{let moving_group=e.state.groups.remove(source_index);let target_index=e.state.groups.iter().position(|g|Some(g.id)==target_group).unwrap();e.state.groups.insert(target_index+usize::from(above),moving_group);}
                    }
                    e.state.layers.splice(at..at,moving);e.state.active=e.state.layers.iter().position(|l|l.id==active).unwrap();
                    e.normalize_groups();
                },
                "translate"=>{for (i,layer)in translated{e.state.layers[i]=layer;}},
                _=>{let g=&mut e.state.groups[group.unwrap()];if let Some(name)=v["name"].as_str(){g.name=name.chars().take(100).collect();}if let Some(b)=v["visible"].as_bool(){g.visible=b;}if let Some(b)=v["locked"].as_bool(){g.locked=b;}if let Some(b)=v["collapsed"].as_bool(){g.collapsed=b;}if let Some(n)=v["opacity"].as_f64(){g.opacity=(n as f32).clamp(0.0,1.0);}if let Some(mode)=v["blend"].as_str(){g.blend=mode.into();}}
            }
            if matches!(action,"create"|"member"|"move"){
                let destination=if action=="member"{id}else{parent};
                let ancestors:Vec<_>=e.group_path(destination).iter().map(|g|g.id).collect();
                for g in &mut e.state.groups{if ancestors.contains(&g.id){g.collapsed=false;}}
            }
            if matches!(action,"create"|"member"|"removeMember"|"ungroup"){e.normalize_groups();}
            e.refresh_groups();
        });let mut info=self.info();if action=="create"{info["createdGroupId"]=json!(self.state.groups.iter().map(|g|g.id).max().unwrap());}Ok(info)
    }
}
