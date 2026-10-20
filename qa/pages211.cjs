const {chromium}=require('C:/Users/15072/Desktop/TIM/test/qa/node_modules/playwright');
const fs=require('fs');
(async()=>{
 const browser=await chromium.launch({headless:true,channel:'msedge'});
 const page=await browser.newPage({viewport:{width:1440,height:1000}});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('http://127.0.0.1:18765/');
 for(const value of ['¥1.99','¥19.99','¥29.99','10 次 / 周'])await page.getByText(value,{exact:true}).waitFor();
 await page.screenshot({path:'qa/output/site211-desktop.png',fullPage:true});
 await page.setViewportSize({width:390,height:844});await page.screenshot({path:'qa/output/site211-mobile.png',fullPage:true});
 if(await page.locator('body').evaluate(e=>e.scrollWidth>innerWidth))throw Error('Mobile overflow');
 await page.goto('http://127.0.0.1:18765/admin/');
 await page.getByLabel('管理员密码').fill(fs.readFileSync('private/管理员凭据.txt','utf8').split('管理员密码：')[1].split('\n')[0]);
 await page.getByRole('button',{name:'登录后台',exact:true}).click();
 await page.locator('#dashboard').waitFor({state:'visible'});
 for(const name of ['兑换码','操作记录','App 弹窗','用户管理']){
   const b=page.getByRole('button',{name,exact:true});await b.click();
   if(await b.getAttribute('aria-selected')!=='true')throw Error('Inactive tab: '+name);
   if(await page.locator('#dashboard nav button:not(.secondary)').count()!==1)throw Error('Multiple active tabs');
   if(await b.evaluate(e=>getComputedStyle(e).backgroundColor)!=='rgb(40, 36, 60)')throw Error('Wrong active color');
 }
 await page.getByRole('button',{name:'App 弹窗',exact:true}).click();
 await page.screenshot({path:'qa/output/admin211-mobile.png',fullPage:true});
 if(errors.length)throw Error(errors.join('\n'));
 console.log('211 page QA passed: pricing, weekly quota, mobile width, all four active tab colors');
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
