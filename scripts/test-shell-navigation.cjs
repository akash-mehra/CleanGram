const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const shell = fs.readFileSync('app/src/main/assets/instagram-shell.js', 'utf8');
const blocking = fs.readFileSync('app/src/main/assets/distraction-blocking.js', 'utf8');

function page(path = '/', options = {}) {
  const messages = [], timers = [], assignments = [], events = {}, observers = [];
  const location = {origin:'https://www.instagram.com', pathname:path,
    get href(){return this.origin+this.pathname;},
    assign(value){assignments.push(value);this.pathname=value;},
    replace(value){assignments.push(value);this.pathname=value;}};
  const history = {};
  for (const method of ['pushState','replaceState']) history[method] = (state,title,url) => {
    if(url!=null) location.pathname = new URL(url,location.href).pathname;
  };
  function style(item={}) {
    const values={};return {setProperty:(key,value)=>{values[key]=value;if(key==='display')item.hidden=value==='none';},
      getPropertyValue:key=>values[key]||'',getPropertyPriority:()=>'',removeProperty:key=>{delete values[key];if(key==='display')item.hidden=false;}};
  }
  function control(item) {
    return {href:item.path?location.origin+item.path:undefined, textContent:item.label||'',
      getAttribute:name=>name==='aria-label'?item.aria||'':'',querySelector:s=>item.profileIcon && s.includes('Profile')?{}:null,
      closest:selector=>item.inArticle?{}:item.inMain && selector==='main'?{}:null,
      getBoundingClientRect:()=>({height:24,top:10,bottom:34}),style:style(item),
      click(){item.clicked=true;if(item.path&&!item.broken)history.pushState({},'',item.path);}};
  }
  const links=(options.links||[]).map(control), buttons=(options.buttons||[]).map(control);
  const navs=(options.navs||[]).map(item=>{
    const actions=(item.actions|| (item.landmarkLinks?[{path:'/'},{path:'/direct/inbox/'}]:[])).map(control);
    return {getBoundingClientRect:()=>({height:item.height,top:item.top,bottom:item.top+item.height}),closest:()=>null,
      querySelector:s=>s==='a[href="/"]'?actions.find(c=>c.href?.endsWith('.com/')):
        s==='a[href="/direct/inbox/"]'?actions.find(c=>c.href?.endsWith('/direct/inbox/')):item.containsContent?{}:null,
      querySelectorAll:()=>actions,style:style(item),actions};
  });
  for(let i=0;i<navs.length;i++) if(options.navs[i].generic) {
    links.push(...navs[i].actions);for(const action of navs[i].actions)action.parentElement=navs[i];
  }
  const stylesheet={textContent:''},root={setAttribute(){},toggleAttribute(){},appendChild(){}},body={setAttribute(){}};
  const document = {documentElement:options.noDocument?null:root,body,
    getElementById:()=>stylesheet,querySelectorAll(s){
      if(s==='a[href]')return links;
      if(s==='a[href], button, [role="button"]')return [...links,...buttons];
      if(s==='nav, [role="navigation"]')return navs.filter((n,i)=>!options.navs[i].generic);
      return [];
    },addEventListener:(name,callback)=>events[name]=callback};
  const window = {innerHeight:800,getComputedStyle:()=>({overflowY:'visible',position:'static'}),CleanGramShell:{postMessage:payload=>messages.push(JSON.parse(payload))},
    addEventListener:(name,callback)=>events[name]=callback};
  const context=vm.createContext({window,document,location,history,URL,
    setTimeout:callback=>timers.push(callback),requestAnimationFrame:callback=>callback(),
    MutationObserver:class{constructor(callback){observers.push(callback)}observe(){}}});
  if(options.withBlocking)vm.runInContext(blocking,context);
  vm.runInContext(shell,context);
  return {context,window,document,location,history,links,navs,stylesheet,messages,assignments,events,
    flush:()=>timers.splice(0).forEach(fn=>fn())};
}

test('native Home and Messages use Instagram links and report actual SPA routes',()=>{
  const p=page('/',{links:[{path:'/direct/inbox/'},{path:'/'}],withBlocking:true});
  assert(p.window.__cleanGramShell.navigate('messages'));p.flush();
  assert.equal(p.location.pathname,'/direct/inbox/');assert.equal(p.messages.at(-1).path,'/direct/inbox/');
  assert(p.window.__cleanGramShell.navigate('home'));p.flush();
  assert.equal(p.location.pathname,'/');assert.equal(p.assignments.length,0);
});

test('normal website navigation is the fallback when no route link exists',()=>{
  const p=page('/');assert(p.window.__cleanGramShell.navigate('messages'));p.flush();
  assert.deepEqual(p.assignments,['/direct/inbox/']);
});

test('an authentication redirect is not replaced by a pending native navigation',()=>{
  const p=page('/');p.window.__cleanGramShell.navigate('messages');
  p.location.pathname='/accounts/login/';p.flush();assert.equal(p.assignments.length,0);
});

test('Profile uses a labelled self-profile link and never a feed avatar',()=>{
  const p=page('/',{links:[{path:'/other.person/'},{path:'/my.account/',profileIcon:true}]});
  assert(p.window.__cleanGramShell.navigate('profile'));p.flush();assert.equal(p.location.pathname,'/my.account/');
  const unknown=page('/',{links:[{path:'/other.person/'}]});
  assert.equal(unknown.window.__cleanGramShell.navigate('profile'),false);
  assert.equal(unknown.assignments.length,0);
});

test('native integration never hides a feed, large landmark, form or unknown navigation',()=>{
  const navs=[{height:700,top:0,landmarkLinks:true},{height:60,top:740,landmarkLinks:true,containsContent:true},
    {height:60,top:740,landmarkLinks:false},{height:64,top:736,landmarkLinks:true}];
  page('/',{navs});assert.deepEqual(navs.map(n=>!!n.hidden),[false,false,false,true]);
});

test('client-side Back and native navigation stay compatible with discovery blocking',()=>{
  const p=page('/direct/inbox/',{withBlocking:true});
  p.history.pushState({},'','/reels/');assert.equal(p.location.pathname,'/direct/inbox/');
  p.history.replaceState({},'','/explore/');assert.equal(p.location.pathname,'/direct/inbox/');
  p.history.pushState({},'','/p/shared/');assert.equal(p.location.pathname,'/p/shared/');
  p.location.pathname='/';p.events.popstate();assert.equal(p.messages.at(-1).path,'/');
});

test('reinjection is idempotent and document-start can safely run before the DOM exists',()=>{
  const p=page('/');const navigate=p.window.__cleanGramShell.navigate;
  vm.runInContext(shell,p.context);assert.equal(p.window.__cleanGramShell.navigate,navigate);
  const early=page('/',{noDocument:true});assert.equal(early.window.__cleanGramShell,undefined);
});

test('saved mute preference applies to dynamically inserted media without duplicate observers',()=>{
  const source=fs.readFileSync('app/src/main/assets/media-preferences.js','utf8');
  const media=[{muted:false}],events={},observers=[];
  const context=vm.createContext({window:{__cleangramMuted:true},document:{documentElement:{},
    querySelectorAll:()=>media,addEventListener:(name,fn)=>events[name]=fn},
    MutationObserver:class{constructor(fn){observers.push(fn)}observe(){}}});
  vm.runInContext(source,context);assert.equal(media[0].muted,true);
  context.window.__cleangramMuted=false;vm.runInContext(source,context);assert.equal(media[0].muted,false);
  media.push({muted:true});observers[0]();assert.equal(media[1].muted,false);assert.equal(observers.length,1);
});


test('an account named Profile inside a post is never treated as the self profile',()=>{
  const p=page('/',{links:[{path:'/profile/',label:'Profile',inArticle:true}]});
  assert.equal(p.window.__cleanGramShell.navigate('profile'),false);
});

test('repeated DOM refreshes do not flood native controls with unchanged routes',()=>{
  const p=page('/');const count=p.messages.length;
  p.window.__cleanGramShell.refresh();p.window.__cleanGramShell.refresh();
  assert.equal(p.messages.length,count);
});


test('Notifications uses an existing site link and preserves its client-side route',()=>{
 const p=page('/',{links:[{path:'/notifications/'}]});
 assert(p.window.__cleanGramShell.navigate('notifications'));p.flush();
 assert.equal(p.location.pathname,'/notifications/');assert.equal(p.assignments.length,0);
});
test('button-based Notifications opens the website panel without a forced reload',()=>{
 const button={aria:'Notifications'};const p=page('/',{buttons:[button]});
 assert(p.window.__cleanGramShell.navigate('notifications'));p.flush();
 assert(button.clicked);assert.equal(p.assignments.length,0);
});
test('Notifications falls back to an ordinary Instagram activity page',()=>{
 const p=page('/');p.window.__cleanGramShell.navigate('notifications');p.flush();
 assert.deepEqual(p.assignments,['/accounts/activity/']);
});
test('generic compact navigation is replaced without relying on nav semantics',()=>{
 const nav={generic:true,height:64,top:736,landmarkLinks:true};page('/',{navs:[nav]});assert(nav.hidden);
});
test('Create and other unknown actions survive duplicate shortcut suppression',()=>{
 const home={path:'/'},messages={path:'/direct/inbox/'},create={aria:'Create'};
 const nav={generic:true,height:64,top:736,actions:[home,messages,create]};
 page('/',{navs:[nav]});assert(!nav.hidden);assert(home.hidden);assert(messages.hidden);assert(!create.hidden);
});
test('website navigation can be restored and clearance follows keyboard changes',()=>{
 const nav={height:64,top:736,landmarkLinks:true};const p=page('/',{navs:[nav]});
 assert(nav.hidden);p.window.__cleanGramShell.setClearance(0,0,true);assert(!nav.hidden);
 assert(p.stylesheet.textContent.includes('padding-bottom:0px'));
 p.window.__cleanGramShell.setClearance(64,88,false);assert(nav.hidden);
 assert(p.stylesheet.textContent.includes('padding-bottom:88px'));
});
test('the latest native navigation wins over a pending fallback',()=>{
 const p=page('/');p.window.__cleanGramShell.navigate('messages');p.window.__cleanGramShell.navigate('notifications');p.flush();
 assert.deepEqual(p.assignments,['/accounts/activity/']);
});
test('Back cancels pending native fallback navigation',()=>{
 const p=page('/');p.window.__cleanGramShell.navigate('messages');p.events.popstate();p.flush();
 assert.deepEqual(p.assignments,[]);
});
