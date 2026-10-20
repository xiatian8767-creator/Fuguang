const {chromium}=require('C:/Users/15072/Desktop/TIM/test/qa/node_modules/playwright');
const fs=require('fs');
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 try{
  const page=await browser.newPage();
  const base='https://fuguang.startimetable.xyz';
  await page.goto(base+'/admin/');
  const credentials=fs.readFileSync('private/管理员凭据.txt','utf8');
  await page.getByLabel('管理员账号').fill(credentials.split('管理员账号：')[1].split('\n')[0]);
  await page.getByLabel('管理员密码').fill(credentials.split('管理员密码：')[1].split('\n')[0]);
  await page.getByRole('button',{name:'登录后台',exact:true}).click();
  await page.locator('#dashboard').waitFor({state:'visible'});
  await page.getByRole('button',{name:'App 弹窗',exact:true}).click();
  const session=await (await page.request.get(base+'/api/admin/session')).json();
  const title='acceptance_delete_'+Date.now();
  const created=await page.request.post(base+'/api/admin/campaigns',{headers:{'X-CSRF-Token':session.csrf},data:{title,enabled:false}});
  if(!created.ok())throw Error('Fixture create failed');
  const {id}=await created.json();
  try{
   await page.getByRole('button',{name:'App 弹窗',exact:true}).click();
   const row=page.locator('#campaignTable tr').filter({hasText:title});
   await row.waitFor();
   page.once('dialog',d=>d.dismiss());
   await row.getByRole('button',{name:'Reset',exact:true}).click();
   let current=(await (await page.request.get(base+'/api/admin/campaigns')).json()).find(c=>c.id===id);
   if(current.revision!==1)throw Error('Cancelled reset changed revision');
   page.once('dialog',d=>d.accept());
   await row.getByRole('button',{name:'Reset',exact:true}).click();
   await page.getByText('展示记录已重置',{exact:true}).waitFor();
   current=(await (await page.request.get(base+'/api/admin/campaigns')).json()).find(c=>c.id===id);
   if(current.revision!==2||current.enabled!==false||current.title!==title)throw Error('Reset config/revision');
   page.once('dialog',d=>d.dismiss());
   await row.getByRole('button',{name:'删除',exact:true}).click();
   if(await row.count()!==1)throw Error('Cancel removed row');
   page.once('dialog',d=>d.accept());
   await row.getByRole('button',{name:'删除',exact:true}).click();
   await row.waitFor({state:'detached'});
   const entries=await (await page.request.get(base+'/api/admin/campaigns')).json();
   if(entries.some(c=>c.id===id))throw Error('Campaign still exists');
   console.log('Production reset and delete passed: confirmation cancellation, reset revision, unchanged config and deletion');
  }finally{
   const entries=await (await page.request.get(base+'/api/admin/campaigns')).json();
   const own=entries.find(c=>c.id===id&&c.title===title);
   if(own)await page.request.delete(base+'/api/admin/campaigns/'+id,{headers:{'X-CSRF-Token':session.csrf},data:{revision:own.revision}});
   await page.getByRole('button',{name:'退出',exact:true}).click();
  }
 }finally{await browser.close()}
})().catch(e=>{console.error(e.message);process.exit(1)});
