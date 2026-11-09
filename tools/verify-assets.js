const fs=require('node:fs');const path=require('node:path');
const root=path.resolve(__dirname,'..'),src=JSON.parse(fs.readFileSync('C:/Users/toins/OneDrive/Documentos/animações/santoryu_cespada.bbmodel'));
const file=path.join(root,'src/main/resources/assets/fightingarts/animations/santoryu.json'),data=JSON.parse(fs.readFileSync(file));
const rig=JSON.parse(fs.readFileSync(path.join(root,'src/main/resources/assets/fightingarts/styles/santoryu_rig.json')));
const check=(v,p)=>{if(typeof v==='number'&&!Number.isFinite(v))throw Error(`nonfinite ${p}`);if(Array.isArray(v))v.forEach((x,i)=>check(x,`${p}[${i}]`));else if(v&&typeof v==='object')for(const[k,x]of Object.entries(v))check(x,`${p}.${k}`)};
for(const a of src.animations.filter(a=>!['walk_left','walk_right'].includes(a.name))){const id=`fa_${a.name}`,full=data.animations[id],up=data.animations[`${id}_upper`],legs=data.animations[`${id}_legs`];if(!full||!up||!legs)throw Error(`missing clip ${id}`);const sourceLength=Math.max(a.length,...Object.values(a.animators||{}).flatMap(x=>(x.keyframes||[]).map(k=>k.time)));if(full.animation_length!==sourceLength)throw Error(`duration ${id}`);const all=new Set([...Object.keys(up.bones),...Object.keys(legs.bones)]);if(all.size!==Object.keys(full.bones).length||Object.keys(full.bones).some(b=>!all.has(b)))throw Error(`mask partition ${id}`);for(const clip of [full,up,legs])check(clip,id);}
if(Object.keys(data.animations).some(x=>/walk_(left|right)/.test(x)))throw Error('discarded walk direction remains');
const groups=new Map(rig.groups.map(g=>[g.name,g]));for(const g of rig.groups)if(g.parent&&!groups.has(g.parent))throw Error(`missing group parent ${g.name}`);for(const c of rig.cubes)if(!groups.has(c.parent))throw Error(`missing cube parent ${c.name}`);if(rig.cubes.length!==src.elements.length)throw Error('cube count mismatch');
for(const x of Object.values(rig.sword_attachments)){if(!groups.has(x.modelBone)||!groups.has(x.adjustment))throw Error('missing attachment locator');}
const png=fs.readFileSync(path.join(root,'src/main/resources/assets/fightingarts',rig.texture));if(png.toString('hex',0,8)!=='89504e470d0a1a0a'||png[25]!==6)throw Error('texture not RGBA PNG');
console.log(`PASS: ${src.animations.length-2} clips, full/upper/legs partitions, finite values, ${rig.groups.length} groups, ${rig.cubes.length} cubes, 4 locators, RGBA PNG.`);

