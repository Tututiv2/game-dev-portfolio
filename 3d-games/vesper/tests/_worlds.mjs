import puppeteer from 'puppeteer-core';
import fs from 'fs'; import path from 'path'; import { pathToFileURL } from 'url';
const FILE = path.resolve('TREES.html');
const b = await puppeteer.launch({ executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',
  headless:'new', args:['--use-angle=default','--enable-gpu','--ignore-gpu-blocklist'],
  defaultViewport:{width:1600,height:950}, protocolTimeout:300000 });
const p = await b.newPage();
p.on('pageerror', e => console.log('PAGEERROR', e.message));
p.on('console', m => { if (m.type()==='error') console.log('ERR', m.text().slice(0,160)); });
await p.goto(pathToFileURL(FILE).href, { waitUntil:'domcontentloaded' });
await p.waitForFunction('window.__T && window.__T.frames>=8', {timeout:60000});
await new Promise(r=>setTimeout(r,600));

const libs = await p.evaluate(()=>window.__T.libs());
console.log('library on load:', Object.entries(libs).map(([k,v])=>`${k}=${v.length}`).join(' '));
console.log('worlds:', (await p.evaluate(()=>window.__T.worlds())).join(' '));

// show the OWNER'S 18 on each world, so the palette is what changes
await p.evaluate(()=>{ window.__T.mode('library'); window.__T.libAll(true); window.__T.set({night:true,size:1}); });
for (const w of ['verdant','crystal','fungal','inferno','desert','glacier']) {
  await p.evaluate(k=>{ window.__T.world(k); window.__T.snap(); }, w);
  await new Promise(r=>setTimeout(r,700));
  const s = await p.screenshot({encoding:'base64'});
  fs.writeFileSync(path.join('shots','trees',`w-${w}.png`), Buffer.from(s,'base64'));
  const m = await p.evaluate(async (b64)=>{
    const i=new Image(); i.src='data:image/png;base64,'+b64; await i.decode();
    const c=document.createElement('canvas'); c.width=400;c.height=240;
    const x=c.getContext('2d'); x.drawImage(i,0,0,400,240);
    const d=x.getImageData(0,0,400,240).data; const seen=new Set(); let sum=0;
    for(let k=0;k<d.length;k+=4){ sum+=(d[k]+d[k+1]+d[k+2])/3;
      seen.add((d[k]>>3<<10)|(d[k+1]>>3<<5)|(d[k+2]>>3)); }
    return { mean:+(sum/(d.length/4)).toFixed(1), cols:seen.size };
  }, s);
  const n = await p.evaluate(()=>window.__T.trees.length);
  console.log(`  ${w.padEnd(9)} ${n} trees on the shelf   ${JSON.stringify(m)}`);
}
await b.close();
