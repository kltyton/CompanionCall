(function () {
 "use strict";
 function init() {
  var source=document.getElementById("state"), candidate=document.getElementById("candidate"), commands=document.getElementById("commands");
  var hud=document.body.getAttribute("data-view")==="hud", h=Vue.h;
  function reportFailure(error,component,phase,state){
   var context="";
   if(state&&Array.isArray(state.pets)){
    var selected=null;
    for(var i=0;i<state.pets.length;i++)if(state.pets[i].id===state.selected)selected=state.pets[i];
    context=" pets="+state.pets.length+" previews="+state.previewsEnabled+" selectedType="+(selected?selected.type:"none")+" health="+(selected?selected.health:"none")+" maxHealth="+(selected?selected.maxHealth:"none");
   }
   console.error("[CompanionCall UI] view="+(hud?"hud":"selection")+" component="+component+" phase="+phase+context+"\n"+(error&&error.stack?String(error.stack):String(error)));
  }
  function health(pet){return Math.round(pet.health)+" / "+Math.round(pet.maxHealth);}
  function meter(pet,texts){return pet.maxHealth>0?h(McUIVue.McProgress,{"class":"cc-meter",value:pet.health,max:pet.maxHealth,showValue:false,"aria-label":texts.health+" "+health(pet)}):null;}
  var PetCard=Vue.defineComponent({
   props:["pet","index","front","texts","previews"],
   render:function(){
    var pet=this.pet,texts=this.texts;
     return h(McUIVue.McCard,{tag:"div",clickable:true,"class":"cc-card-surface"},{"default":function(){return [
     h("div",{"class":"cc-card-top"},[h("span",{"class":"cc-card-number"},[(this.index+1<10?"0":"")+(this.index+1)]),this.front?h("span",{"class":"cc-selected"},[texts.selected]):null]),
      this.previews?h("div",{"class":"cc-model-stage"},[h("span",{"class":"cc-model-light","aria-hidden":"true"}),h("companion-preview",{"pet-id":pet.id,"aria-label":pet.name}),h("span",{"class":"cc-model-unavailable"},[texts.preview_unavailable])]):null,
      h(McUIVue.McCardItem,{"class":"cc-card-info"},{
       title:function(){return pet.name;},
       subtitle:function(){return pet.travelModes.map(function(mode){return texts[mode.toLowerCase()];}).join(" / ");},
       "default":function(){return pet.maxHealth>0?[h("div",{"class":"cc-health"},[meter(pet,texts),h("span",{"class":"cc-health-value"},[health(pet)])])]:[];}
      })
     ].concat([h("span",{"class":"cc-card-sheen","aria-hidden":"true"})]);}.bind(this)});
   }
  });
  var app=Vue.createApp({
   data:function(){return {state:null,front:0,position:0,hovered:null,stageWidth:1440,management:false,tab:"active"};},
   methods:{
    text:function(key){return this.state.texts[key];},
    modes:function(pet){var self=this;return pet.travelModes.map(function(mode){return self.text(mode.toLowerCase());}).join(" / ");},
    health:health,
    meter:function(pet){return meter(pet,this.state.texts);},
    send:function(action,id){
     commands.setAttribute("data-action",action);commands.setAttribute("data-id",id||"");
     commands.dispatchEvent(window.createEvent("companion-command",false));
    },
    button:function(label,action,id,disabled){
     var self=this;
       return h(McUIVue.McButton,{"class":"cc-manage-button"+(action==="add_ridden"?" cc-manage-button--ridden":""),size:"small",variant:action==="blacklist"?"error":action==="close"?"normal":"primary",disabled:!!disabled,onClick:function(){self.send(action,id);}},
      {"default":function(){return self.text(label);}});
    },
    manager:function(){
     var self=this,blocked=this.tab==="blacklist",list=blocked?this.state.blocked:this.state.pets,rows=[];
     for(var i=0;i<list.length;i++){
      var pet=list[i],actions=[];
      if(blocked)actions.push(this.button("restore","restore",pet.id,!pet.valid));
      else{actions.push(this.button("set_selected","select",pet.id,pet.id===this.state.selected));actions.push(this.button("remove","blacklist",pet.id,false));}
      rows.push(h("div",{key:pet.id,"class":"cc-management-row"},[
       h("div",{"class":"cc-management-identity"},[h("div",{"class":"cc-hud-name"},[pet.name]),
         h("div",{"class":"cc-muted"},[(this.state.typeNames[pet.id]===pet.name?"":this.state.typeNames[pet.id]+" · ")+pet.id.slice(0,8)+(pet.valid?"":" · "+this.text("unavailable"))])]),
       h("div",{"class":"cc-management-actions"},actions)]));
     }
     if(!rows.length)rows.push(h("div",{"class":"cc-management-empty"},[this.text(blocked?"blacklist_empty":"empty")]));
     var ridden=this.state.ridden;
     return h(McUIVue.McCard,{"class":"cc-management"},{"default":function(){return [
      h("div",{"class":"cc-management-heading"},[h("h2",{},[self.text("manage")]),self.button("close","close","",false)]),
      h("div",{"class":"cc-management-tabs"},[
       h(McUIVue.McButton,{onClick:function(){self.tab="active";},disabled:!blocked},{"default":function(){return self.text("active")+" ("+self.state.pets.length+")";}}),
       h(McUIVue.McButton,{onClick:function(){self.tab="blacklist";},disabled:blocked},{"default":function(){return self.text("blacklist")+" ("+self.state.blocked.length+")";}})]),
      h("div",{"class":"cc-management-list"},rows),
      h("div",{"class":"cc-management-ridden"},[
        h("div",{"class":"cc-management-identity"},[h("div",{},[self.text("current_ridden")]),h("div",{"class":"cc-muted"},[ridden?ridden.name+(ridden.name===self.state.typeNames[ridden.id]?"":" · "+self.state.typeNames[ridden.id]):self.text("not_riding")])]),
       self.button("add_ridden","add_ridden","",!ridden)]),
      h("div",{"class":"cc-management-note"},[self.text("remove_help"),h("br"),self.text("add_key_help")])
     ];}});
    },
    select:function(index){
     if(!this.state||!this.state.pets.length)return;
     var count=this.state.pets.length;
     index=(index+count)%count;
     var delta=index-this.front;
      if(count>=3)delta-=Math.round(delta/count)*count;
     this.target+=delta;this.front=index;this.hovered=null;
      candidate.setAttribute("data-pet",this.state.pets[index].id);
      if(this.reducedMotion){this.position=this.target;this.velocity=0;}
      else this.wake();
    },
    shift:function(direction){this.select(this.front+direction);},
     wake:function(){if(!this.frame&&this.animate&&!document.hidden)this.frame=requestAnimationFrame(this.animate);},
    update:function(){
     if(!source.textContent)return;
     var next=JSON.parse(source.textContent), previous=this.state&&this.state.pets[this.front];
     var wanted=previous?previous.id:next.selected,index=-1;
     for(var i=0;i<next.pets.length;i++)if(next.pets[i].id===wanted)index=i;
     if(index<0)for(var j=0;j<next.pets.length;j++)if(next.pets[j].id===next.selected)index=j;
     index=index<0?0:index;
     var changed=!this.state||this.state.pets.map(function(p){return p.id;}).join(",")!==next.pets.map(function(p){return p.id;}).join(",");
     this.state=next;this.front=index;
     if(changed){this.position=index;this.target=index;this.velocity=0;}
     if(this.hovered&&!next.pets.some(function(pet){return pet.id===this.hovered;},this))this.hovered=null;
     candidate.setAttribute("data-pet",next.pets.length?next.pets[index].id:"");
    },
    row:function(label,value){return h("div",{"class":"cc-detail-row"},[h("span",{"class":"cc-label"},[this.text(label)]),h("span",{"class":"cc-detail-value"},[String(value)])]);},
    hideDetail:function(){var self=this;this.hideTimer=setTimeout(function(){self.hovered=null;},160);},
    keepDetail:function(){if(this.hideTimer)clearTimeout(this.hideTimer);},
    detail:function(pet){
     var rows=[this.row("type",this.state.typeNames[pet.id]),
      this.row("dimension",this.state.dimensionNames[pet.id]),this.row("position",Math.round(pet.x)+", "+Math.round(pet.y)+", "+Math.round(pet.z)),
      this.row("sitting",this.text(pet.sitting?"sitting":"standing")),this.row("movement",this.modes(pet))];
     if(pet.maxHealth>0)rows.splice(1,0,this.row("health",this.health(pet)));
     if(pet.speed>0)rows.push(this.row("speed",pet.speed.toFixed(3)));
     if(pet.jump>0)rows.push(this.row("jump",pet.jump.toFixed(3)));
     var self=this;
      return h(McUIVue.McCard,{tag:"aside","class":"cc-details",role:"tooltip",onMouseenter:function(){self.keepDetail();},onMouseleave:function(){self.hideDetail();}},
       {"default":function(){return [h(McUIVue.McCardTitle,{"class":"cc-detail-name"},{"default":function(){return pet.name;}}),
        h("div",{"class":"cc-muted"},[self.text(self.state.loaded.indexOf(pet.id)>=0?"current":"recorded")]),h("div",{"class":"cc-detail-grid"},rows)];}});
    },
    card:function(pet,index,offset){
        var self=this,abs=Math.abs(offset),front=index===this.front,hovered=this.hovered===pet.id&&!front;
        var fit=Math.min(1,Math.max(.45,this.stageWidth/880)),step=155*fit,x=offset*step,y=(18*offset*offset-16*Math.max(0,1-abs))*fit,scale=fit*Math.max(.68,1-abs*.14),angle=offset*6,depth=1-Math.min(abs/3,1);
      return h("article",{key:pet.id,tabindex:front?"0":"-1",role:"option","aria-selected":String(front),"aria-label":pet.name,
       "class":"cc-pet-card"+(front?" cc-pet-card--front":"")+(hovered?" cc-pet-card--hovered":""),
        style:{transform:"translate("+x+"px,"+y+"px) rotate("+angle+"deg) scale("+scale+")",zIndex:hovered?30:10+Math.round(depth*10)},
      onMouseenter:function(){self.keepDetail();self.hovered=pet.id;},onMouseleave:function(){self.hideDetail();},
      onFocus:function(){self.hovered=pet.id;},onBlur:function(){self.hovered=null;},onClick:function(){self.select(index);}
      },[h(PetCard,{pet:pet,index:index,front:front,texts:this.state.texts,previews:this.state.previewsEnabled})]);
    }
   },
   mounted:function(){
     var self=this;this.target=0;this.velocity=0;this.lastFrame=0;this.frame=0;this.update();
    this.observer=new MutationObserver(function(){self.update();});this.observer.observe(source,{childList:true,characterData:true,subtree:true});
    if(hud)return;
     this.reducedMotion=typeof window.matchMedia==="function"&&window.matchMedia("(prefers-reduced-motion: reduce)").matches;
     this.resizeObserver=new ResizeObserver(function(entries){if(entries.length){var width=entries[0].contentRect.width;if(Number.isFinite(width)&&width>0)self.stageWidth=width;}});
    this.resizeObserver.observe(document.getElementById("selection"));
     this.onWheel=function(event){if(self.management||event.target.closest&&event.target.closest(".cc-details"))return;event.preventDefault();if(event.deltaY!==0)self.shift(event.deltaY>0?1:-1);};
     this.onKeydown=function(event){if(self.management)return;if(event.key==="ArrowLeft"){event.preventDefault();self.shift(-1);}if(event.key==="ArrowRight"){event.preventDefault();self.shift(1);}};
     document.addEventListener("wheel",this.onWheel);
     document.addEventListener("keydown",this.onKeydown);
     this.animate=function(time){
       self.frame=0;
      var elapsed=self.lastFrame?(time-self.lastFrame)/1000:.016;self.lastFrame=time;
      var distance=self.position-self.target;
      if(Math.abs(distance)>.0005||Math.abs(self.velocity)>.001){
        var decay=11.2,frequency=8.4,weight=(self.velocity+decay*distance)/frequency;
        var phase=frequency*elapsed,cosine=Math.cos(phase),sine=Math.sin(phase),fade=Math.exp(-decay*elapsed);
        var wave=distance*cosine+weight*sine;
        self.position=self.target+fade*wave;
        self.velocity=fade*(-decay*wave-frequency*distance*sine+frequency*weight*cosine);
      }else if(self.position!==self.target){
       self.position=self.target;self.velocity=0;
     }
      if(Math.abs(self.position-self.target)>.0005||Math.abs(self.velocity)>.001)self.wake();
      else{self.position=self.target;self.velocity=0;self.lastFrame=0;}
     };
     this.onVisibility=function(){if(document.hidden){if(self.frame)cancelAnimationFrame(self.frame);self.frame=0;self.lastFrame=0;}else if(self.position!==self.target)self.wake();};
     document.addEventListener("visibilitychange",this.onVisibility);
   },
    beforeUnmount:function(){if(this.observer)this.observer.disconnect();if(this.resizeObserver)this.resizeObserver.disconnect();if(this.frame)cancelAnimationFrame(this.frame);if(this.hideTimer)clearTimeout(this.hideTimer);if(this.onWheel)document.removeEventListener("wheel",this.onWheel);if(this.onKeydown)document.removeEventListener("keydown",this.onKeydown);if(this.onVisibility)document.removeEventListener("visibilitychange",this.onVisibility);},
   render:function(){
    var self=this;
    if(!this.state)return h("div",{"class":"cc-theme cc-empty"},[h("translation",{},["companion_call.status.syncing"])]);
    var pets=this.state.pets;
    if(hud){
     var selected=null;for(var p=0;p<pets.length;p++)if(pets[p].id===this.state.selected)selected=pets[p];
     return h("div",{"class":"cc-theme mc-theme"},[h(McUIVue.McCard,{"class":"cc-hud"},{"default":function(){return [
        selected&&self.state.previewsEnabled?h("div",{"class":"cc-hud-avatar"},[h("companion-preview",{"pet-id":selected.id,view:"portrait","aria-label":selected.name})]):null,
      h("span",{"class":"cc-key cc-hud-key"},[self.text("key")]),h("div",{"class":"cc-hud-body"},[h("div",{"class":"cc-hud-name"},[selected?selected.name:self.text("empty")]),
       h("div",{"class":"cc-hud-status"},[self.state.status]),selected?self.meter(selected):null])];}})]);
    }
    if(this.management&&this.state.managementEnabled)return h("main",{"class":"cc-selector cc-theme mc-theme"},[this.manager()]);
    var content=[];
    if(!pets.length)content.push(h("div",{"class":"cc-empty"},[h("div",{"class":"cc-empty-title"},[this.text("empty")]),h("div",{"class":"cc-muted"},[this.text("empty_help")])]));
    else{
      var cards=[];
      for(var i=0;i<pets.length;i++){var offset=i-this.position;if(pets.length>=3)offset-=Math.round(offset/pets.length)*pets.length;if(Math.abs(offset)<2.65)cards.push(this.card(pets[i],i,offset));}
      content.push(h("div",{"class":"cc-carousel",role:"listbox","aria-label":this.text("choose")},[h("div",{"class":"cc-focus-aura","aria-hidden":"true"})].concat(cards)));
     for(var d=0;d<pets.length;d++)if(pets[d].id===this.hovered)content.push(this.detail(pets[d]));
    }
     content.unshift(h("header",{"class":"cc-header"},[h("h1",{"class":"cc-heading"},[this.text("title")]),pets.length?h("div",{"class":"cc-count","aria-live":"polite"},[(this.front+1<10?"0":"")+(this.front+1)+" / "+(pets.length<10?"0":"")+pets.length]):null]));
     if(this.state.managementEnabled)content.push(h(McUIVue.McButton,{"class":"cc-open-management",variant:"primary",onClick:function(){self.management=true;self.hovered=null;self.send("manage","");}},
     {"default":function(){return self.text("manage");}}));
    content.push(h("footer",{"class":"cc-footer"},[h("div",{"class":"cc-instruction"},[this.text("choose")]),h("div",{},[this.text("release")]),h("div",{"class":"cc-muted"},[this.text("cancel")])]));
    return h("main",{"class":"cc-selector cc-theme mc-theme"},content);
   }
  });
  app.config.errorHandler=function(error,instance,phase){
   var options=instance&&instance.$options,component=options&&(options.name||options.__name)||"CompanionView",root=instance&&instance.$root;
   reportFailure(error,component,phase,root&&root.state);
  };
  app.use(McUIVue.createMcUI({sounds:{enabled:false}}));app.mount("#app");
  document.addEventListener("unload",function(){
   try{app.unmount();}
   catch(error){reportFailure(error,"CompanionView","unload",null);throw error;}
  });
 }
 document.addEventListener("DOMContentLoaded",init);
})();
