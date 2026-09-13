const mc = require('minecraft-protocol');
const client = mc.createClient({host:'127.0.0.1',port:25579,username:'SidebarProbe',auth:'offline',version:'1.21.11'});
let joined=false; const packets=[];
client.on('packet',(data,meta)=>{
 if(meta.name==='login' && meta.state==='play') joined=true;
 if(meta.name.includes('scoreboard') || meta.name.includes('score')) packets.push({name:meta.name,data});
 if(meta.name==='position') client.write('teleport_confirm',{teleportId:data.teleportId});
});
client.on('error',error=>{console.error(error.message);process.exitCode=1});
client.on('kick_disconnect',data=>console.error('KICK',JSON.stringify(data)));
setTimeout(()=>client.chat('/1mill scoreboard'),3000);
setTimeout(()=>client.chat('/1mill scoreboard'),6000);
setTimeout(()=>{
 const removed=packets.some(p=>p.name==='scoreboard_objective'&&p.data.action===1);
 const created=packets.filter(p=>p.name==='scoreboard_objective'&&p.data.action===0).length;
 const display=packets.some(p=>p.name==='scoreboard_display_objective');
 const scores=packets.filter(p=>p.name==='scoreboard_score').length;
 console.log(JSON.stringify({joined,sidebarDisplayed:display,toggledOff:removed,recreated:created>=2,scorePackets:scores,packetNames:[...new Set(packets.map(p=>p.name))]}));
 require('fs').writeFileSync('/tmp/folia-sidebar-packets.json',JSON.stringify(packets,null,2));
 client.end();
 setTimeout(()=>process.exit(joined&&display&&scores>0&&removed&&created>=2?0:1),300);
},10000);
