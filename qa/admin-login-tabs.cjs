const {chromium}=require('C:/Users/15072/Desktop/TIM/test/qa/node_modules/playwright');
const fs=require('fs');
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try{
  const page=await browser.newPage({viewport:{width:1200,height:900}});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  const response=await page.goto('https://fuguang.startimetable.xyz/admin/');
  if(response.headers()['cache-control']!=='no-store')throw Error('Admin cache header');
  const credentials=fs.readFileSync('private/管理员凭据.txt','utf8');
  await page.getByLabel('管理员账号').fill(credentials.split('管理员账号：')[1].split('\n')[0]);
  await page.getByLabel('管理员密码').fill(credentials.split('管理员密码：')[1].split('\n')[0]);
  await page.getByRole('button',{name:'登录后台',exact:true}).click();
  await page.locator('#dashboard').waitFor({state:'visible'});
  const tabs=page.locator('#dashboard nav button');
  if(await tabs.count()!==4)throw Error('Expected four tabs');
  if(await page.locator('#dashboard nav button[aria-selected="true"]').count()!==0)throw Error('Initial selection');
  const initial=await tabs.evaluateAll(es=>es.map(e=>getComputedStyle(e).backgroundColor));
  if(!initial.every(c=>c==='rgb(234, 229, 244)'))throw Error('Initial tab colors');
  await page.screenshot({path:'qa/output/admin-tabs-initial.png'});
  for(const name of ['用户管理','兑换码','操作记录','App 弹窗']){
   const b=page.getByRole('button',{name,exact:true});await b.click();
   if(await page.locator('#dashboard nav button[aria-selected="true"]').count()!==1)throw Error('Selected count');
   if(await b.evaluate(e=>getComputedStyle(e).backgroundColor)!=='rgb(40, 36, 60)')throw Error('Selected color '+name);
   if(await b.getAttribute('aria-selected')!=='true')throw Error('Selected state '+name);
  }
  await page.screenshot({path:'qa/output/admin-tabs-selected.png'});
  await page.getByRole('button',{name:'新建弹窗',exact:true}).click();
  await page.locator('#campaign-editor').waitFor({state:'visible'});
  await page.getByLabel('名称',{exact:true}).fill('UI 验收');
  await page.getByLabel('H5 页面链接',{exact:true}).fill('https://fuguang.startimetable.xyz/updates/2.1.1/');
  await page.locator('#campaignForm select[name=audience]').selectOption('custom');
  await page.getByLabel('已选择的用户 ID',{exact:true}).fill('ui-test-id');
  await page.screenshot({path:'qa/output/admin-campaign-editor-desktop.png'});
  await page.setViewportSize({width:390,height:844});
  await page.screenshot({path:'qa/output/admin-campaign-editor-mobile.png'});
  if(await page.locator('#campaign-editor').evaluate(e=>e.scrollWidth>e.clientWidth))throw Error('Editor overflow');
  let payload;
  await page.route('**/api/admin/campaigns',async route=>{
   if(route.request().method()!=='POST')return route.continue();
   payload=route.request().postDataJSON();
   await route.fulfill({status:200,contentType:'application/json',body:'{"id":"ui-only"}'});
  });
  await page.getByRole('button',{name:'保存',exact:true}).click();
  await page.locator('#campaign-editor').waitFor({state:'hidden'});
  if(!payload||payload.mode!=='web'||payload.width!==88||payload.height!==75||payload.enabled!==false||payload.user_ids[0]!=='ui-test-id'||payload.target_version!==4)throw Error('Campaign mapping');

  if(errors.length)throw Error(errors.join('\n'));
  await page.getByRole('button',{name:'退出',exact:true}).click();
  console.log('Production admin login and four tab colors passed');
 }finally{await browser.close()}
})().catch(e=>{console.error(e.message);process.exit(1)});
