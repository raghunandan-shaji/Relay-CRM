import {element as h, pretty, date, claims, forwardStages} from './ui.js';
const $ = selector => document.querySelector(selector);
let session = null, selected = null, tab = 'Profile', register = false, selection = 0, fieldNumber = 0, noticeTimer;
const notice = (message, error = false) => { clearTimeout(noticeTimer); $('#notice').textContent = message; $('#notice').className = error ? 'error' : ''; noticeTimer = setTimeout(() => $('#notice').textContent = '', 9000); };
async function run(action) { try { await action(); } catch (error) { notice(error.message, true); } }
async function api(path, method = 'GET', body) {
	const requestingSession = session;
  const headers = {Accept:'application/json'};
  if (session) headers.Authorization = `Bearer ${session.token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, {method, headers, body:body === undefined ? undefined : JSON.stringify(body), cache:'no-store'});
  const text = await response.text();
  if (requestingSession && requestingSession !== session) throw new Error('Session changed; please reopen the record.');
  let data;
  try { data = text ? JSON.parse(text) : null; } catch { throw new Error('The server returned an unreadable response.'); }
  if (!response.ok) {
    if (response.status === 401 && session) logout();
    const fallback = {401:'Sign in again; your session is missing or expired.',403:'Your account cannot perform this action.',409:'This record changed. Refresh it before trying again.',429:`Too many requests. Retry in ${response.headers.get('Retry-After') || 'a few'} seconds.`,503:'A service is unavailable. Please retry shortly.'};
    throw new Error(data?.detail || fallback[response.status] || `Request failed (${response.status}).`);
  }
  return data;
}
const customerPath = id => `/api/customers/${id}`;
const button = (label, action, className = '') => h('button', {type:'button', class:className, onclick:() => run(action)}, label);
const badge = value => h('span', {class:`pill ${String(value).toLowerCase()}`}, pretty(value));
const empty = message => h('div', {class:'empty'}, message);
const meta = text => h('p', {class:'meta'}, text);
function nav(current) { for (const name of ['workspace','desk','inbox']) $(`#nav-${name}`).classList.toggle('active', name === current); }
function heading(title, subtitle, eyebrow = 'CUSTOMER WORKSPACE') { return h('div', {class:'page-heading'}, h('div', {}, h('p', {class:'eyebrow'}, eyebrow), h('h1', {}, title), h('p', {class:'muted'}, subtitle))); }
function form(fields, label, submit) {
  const set = h('fieldset');
  for (const field of fields) {
    const id = `field-${++fieldNumber}`;
    let input;
    if (field.options) input = h('select', {id, name:field.name}, field.options.map(option => h('option', {value:option.value ?? option, selected:field.value === (option.value ?? option)}, option.label ?? pretty(option))));
    else input = h(field.type === 'textarea' ? 'textarea' : 'input', {id, name:field.name, type:field.type || 'text', required:field.required, maxlength:field.max, min:field.min, step:field.step, value:field.type === 'textarea' ? undefined : field.value, autocomplete:field.autocomplete || 'off'});
    if (field.type === 'textarea' && field.value) input.textContent = field.value;
    set.append(h('label', {for:id}, field.label), input);
  }
  set.append(h('button', {type:'submit', class:'primary wide'}, label));
  return h('form', {onsubmit:event => { event.preventDefault(); const values = Object.fromEntries(new FormData(event.currentTarget)); set.disabled = true; run(async () => { try { await submit(values); notice('Saved.'); } finally { set.disabled = false; } }); }}, set);
}
function editor(title, description, fields, label, submit) { return h('section', {class:'card'}, h('h2', {}, title), h('p', {class:'muted'}, description), form(fields, label, submit)); }
function paged(host, fetchPage, render, cursorFor) {
  let cursor = null;
  const rows = h('div', {class:'record-list'}), more = button('Load more', load);
  host.append(rows, h('div', {class:'actions'}, more));
  async function load() {
    more.disabled = true;
    try {
      const data = await fetchPage(cursor);
      if (!data.length && !cursor) rows.append(empty('No records yet.'));
      rows.append(...data.map(render));
      if (data.length) cursor = cursorFor(data.at(-1));
      more.hidden = data.length < 20;
    } finally { more.disabled = false; }
  }
  run(load);
}
function logout() { session = null; selected = null; selection++; $('#app').hidden = true; $('#auth').hidden = false; $('#password').value = ''; $('#workspace').replaceChildren(); $('#search-results').replaceChildren(); $('#customer-search').value = ''; $('.skip-link').href='#main'; authMode(false); }
function authMode(value) { register = value; $('#register-fields').hidden = !value; $('#fullName').required = value; $('#password').minLength = value ? 8 : 1; $('#password').autocomplete = value ? 'new-password' : 'current-password'; $('#auth-title').textContent = value ? 'Start a connection.' : 'Welcome back.'; $('#auth-submit').textContent = value ? 'Create account' : 'Sign in'; $('#login-tab').classList.toggle('active', !value); $('#register-tab').classList.toggle('active', value); $('#login-tab').setAttribute('aria-pressed', String(!value)); $('#register-tab').setAttribute('aria-pressed', String(value)); }
$('#login-tab').onclick = () => authMode(false);
$('#register-tab').onclick = () => authMode(true);
$('#logout').onclick = logout;
$('#auth-form').onsubmit = event => {
  event.preventDefault();
  const values = Object.fromEntries(new FormData(event.currentTarget));
  $('#auth-submit').disabled = true;
  run(async () => {
    try {
      if (register) await api('/api/customers', 'POST', {fullName:values.fullName, email:values.email, password:values.password, company:values.company || null});
      const login = await api('/api/auth/login', 'POST', {email:values.email, password:values.password});
      const info = claims(login.accessToken);
      session = {token:login.accessToken, id:login.customerId, staff:String(info.scope || '').split(' ').includes('crm:staff')};
      $('#identity').textContent = values.email; $('#role').textContent = session.staff ? 'Staff workspace' : 'Customer portal';
      $('#nav-desk').hidden = !session.staff; $('#search-wrap').hidden = !session.staff;
      $('#auth').hidden = true; $('#app').hidden = false; $('#password').value = '';
      $('.skip-link').href='#workspace';
      await openCustomer(session.id);
    } finally { $('#auth-submit').disabled = false; }
  });
};
$('#nav-workspace').onclick = () => run(() => openCustomer(selected?.id || session.id));
$('#nav-desk').onclick = () => run(desk);
$('#nav-inbox').onclick = () => run(inbox);
let searchTimer, searchVersion = 0;
$('#customer-search').oninput = () => {
  clearTimeout(searchTimer); const version = ++searchVersion, prefix = $('#customer-search').value.trim();
  $('#search-results').hidden = prefix.length < 2;
  if (prefix.length < 2) return;
  searchTimer = setTimeout(() => run(async () => {
    const data = await api(`/api/customers/search?prefix=${encodeURIComponent(prefix)}&limit=8`);
    if (version !== searchVersion || !session) return;
    $('#search-results').replaceChildren(...(data.length ? data.map(customer => button(`${customer.fullName} · ${customer.company || customer.email}`, async () => { $('#search-results').hidden = true; await openCustomer(customer.id); })) : [h('p', {class:'muted'}, 'No matching customers.')]));
  }), 250);
};
$('#customer-search').onkeydown = event => { if (event.key === 'Escape') $('#search-results').hidden = true; };
async function openCustomer(id) {
  const version = ++selection;
  const profile = await api(customerPath(id));
  if (version !== selection || !session) return;
  selected = profile; tab = 'Profile'; workspace();
}
function workspace(nextTab = tab) {
  tab = nextTab; nav('workspace');
  const customer = selected;
  const panel = h('div');
  const tabs = ['Profile', ...(session.staff ? ['Pipeline'] : []), 'History', 'Activities', 'Support'];
  $('#workspace').replaceChildren(heading(customer.fullName, [customer.company, customer.email].filter(Boolean).join(' · ')),
    h('div', {class:'tabs', 'aria-label':'Customer sections'}, tabs.map(name => button(name, () => workspace(name), name === tab ? 'active' : ''))), panel);
  if (tab === 'Profile') {
    const details = h('dl', {class:'read-grid'}, ['fullName','email','phone','company'].map(key => h('div', {}, h('dt', {}, pretty(key === 'fullName' ? 'Full name' : key)), h('dd', {}, customer[key] || '—'))));
    panel.append(h('section', {class:'card'}, h('h2', {}, 'Customer details'), details));
    if (customer.id === session.id) panel.append(editor('Update your profile', 'Keep your contact details current.', [
      {name:'fullName',label:'Full name',required:true,max:120,value:customer.fullName}, {name:'phone',label:'Phone',max:30,value:customer.phone}, {name:'company',label:'Company',max:160,value:customer.company}
    ], 'Save profile', async data => { selected = await api(customerPath(customer.id), 'PUT', {...data,version:customer.version}); workspace('Profile'); }));
  } else if (tab === 'Pipeline') pipeline(panel, customer);
  else if (tab === 'History') history(panel, customer);
  else if (tab === 'Activities') activities(panel, customer);
  else support(panel, customer);
}
function pipeline(panel, customer) {
  panel.className = 'two-column'; const list = h('section');
  panel.append(editor('New lead', 'Track an enquiry for this customer. A representative is assigned automatically.', [
    {name:'title',label:'Lead title',required:true,max:160},{name:'source',label:'Source',max:80},{name:'notes',label:'Notes',type:'textarea',max:1000}
  ], 'Create lead', async data => { await api(customerPath(customer.id) + '/leads', 'POST', data); workspace('Pipeline'); }), list);
  paged(list, cursor => api(customerPath(customer.id) + '/leads?limit=20' + (cursor ? '&afterId='+cursor : '')), lead => {
    const path = customerPath(customer.id) + '/leads/' + lead.id;
    const row = h('article', {class:'record'}, h('div', {class:'record-head'}, h('h3', {}, lead.title), badge(lead.opportunity?.stage || lead.status)),
      meta(`${lead.source || 'Direct enquiry'} · ${date(lead.createdAt)}`), h('p', {}, lead.notes || ''));
    const actions = h('div', {class:'actions'});
    if (lead.status === 'NEW') actions.append(button('Mark contacted', async () => { await api(path+'/status','PUT',{status:'CONTACTED',version:lead.version}); workspace('Pipeline'); }));
    if (['NEW','CONTACTED'].includes(lead.status)) {
      row.append(form([{name:'amount',label:'Opportunity value',type:'number',required:true,min:'.01',step:'.01'},{name:'currency',label:'Currency',options:['INR','USD','EUR','GBP']}], 'Qualify opportunity', async data => { await api(path+'/opportunity','POST',{amount:data.amount,currency:data.currency,version:lead.version}); workspace('Pipeline'); }));
      actions.append(button('Mark lost', async () => { await api(path+'/status','PUT',{status:'LOST',version:lead.version}); workspace('Pipeline'); }));
    }
    if (lead.opportunity) {
      row.append(h('p', {}, `${lead.opportunity.currency} ${lead.opportunity.amount}`));
      if (!['WON','LOST'].includes(lead.opportunity.stage)) for (const stage of forwardStages(lead.opportunity.stage,['OPEN','PROPOSAL','NEGOTIATION','WON','LOST'])) actions.append(button(pretty(stage), async () => { await api(path+'/opportunity/stage','PUT',{stage,version:lead.version}); workspace('Pipeline'); }));
    }
    row.append(actions); return row;
  }, value => value.id);
}
function history(panel, customer) {
  const list = h('section'); panel.className = session.staff ? 'two-column' : '';
  if (session.staff) panel.append(editor('Record an interaction', 'Log a completed communication. Everything entered here is visible to this customer.', [
    {name:'type',label:'Type',options:['CALL','EMAIL','MEETING','NOTE']},{name:'summary',label:'Summary',required:true,max:160},{name:'details',label:'Details',type:'textarea',max:4000}
  ], 'Record interaction', async data => { await api(customerPath(customer.id)+'/interactions','POST',data); workspace('History'); }));
  panel.append(list);
  paged(list, cursor => api(customerPath(customer.id)+'/interactions?limit=20'+(cursor ? '&beforeAt='+encodeURIComponent(cursor.at)+'&beforeId='+cursor.id : '')), value => h('article',{class:'record'},
    h('div',{class:'record-head'},h('h3',{},value.summary),badge(value.type)),meta(date(value.occurredAt)),h('p',{},value.details || '')), value => ({id:value.id,at:value.occurredAt}));
}
function activities(panel, customer) {
  const list = h('section'); panel.className = session.staff ? 'two-column' : '';
  if (session.staff) panel.append(editor('Plan a follow-up', 'Create a customer-visible task. A representative is assigned automatically.',[
    {name:'type',label:'Type',options:['TASK','CALL','EMAIL','MEETING']},{name:'title',label:'Title',required:true,max:160},{name:'description',label:'Description',type:'textarea',max:4000},{name:'dueAt',label:'Due date and time',type:'datetime-local',required:true}
  ],'Create activity',async data=>{ await api(customerPath(customer.id)+'/activities','POST',{...data,dueAt:new Date(data.dueAt).toISOString()});workspace('Activities'); }));
  panel.append(list);
  paged(list,cursor=>api(customerPath(customer.id)+'/activities?limit=20'+(cursor?'&afterId='+cursor:'')),value=>{
    const row=h('article',{class:'record'},h('div',{class:'record-head'},h('h3',{},value.title),badge(value.status)),meta(`${pretty(value.type)} · Due ${date(value.dueAt)}`),h('p',{},value.description||''));
    if(value.outcome)row.append(h('p',{},value.outcome));
    if(session.staff&&value.status==='OPEN')row.append(form([
      {name:'status',label:'Update status',options:['COMPLETED','CANCELLED']},
      {name:'outcome',label:'Outcome (visible to customer)',type:'textarea',max:1000}
    ],'Save activity',async data=>{await api(customerPath(customer.id)+'/activities/'+value.id+'/status','PUT',{...data,version:value.version});workspace('Activities');}));return row;
  },value=>value.id);
}
function support(panel, customer) {
  const list=h('section');panel.className='two-column';
  panel.append(editor('New support ticket','Start a conversation with the support team.',[
    {name:'subject',label:'Subject',required:true,max:160},{name:'priority',label:'Priority',options:['NORMAL','LOW','HIGH','URGENT']},{name:'message',label:'Message',type:'textarea',required:true,max:4000}
  ],'Create ticket',async data=>{const ticket=await api(customerPath(customer.id)+'/tickets','POST',data);await ticketDetail(customer.id,ticket.id);}),list);
  paged(list,cursor=>api(customerPath(customer.id)+'/tickets?limit=20'+(cursor?'&afterId='+cursor:'')),ticketCard,value=>value.id);
}
function ticketCard(value){return h('article',{class:'record'},h('div',{class:'record-head'},h('h3',{},value.subject),badge(value.status)),meta(`${pretty(value.priority)} priority · ${date(value.updatedAt)}`),h('div',{class:'actions'},button('Open conversation',()=>ticketDetail(value.customerId,value.id))));}
async function ticketDetail(customerId,id){
  const ticket=await api(customerPath(customerId)+'/tickets/'+id);const messageList=h('section'),side=h('section');
  $('#workspace').replaceChildren(button('← Back to customer',async()=>{await openCustomer(customerId);workspace('Support');},'detail-back'),heading(ticket.subject,`${pretty(ticket.priority)} priority · ${pretty(ticket.status)}`,'SUPPORT CONVERSATION'),h('div',{class:'two-column'},side,messageList));
  const path=customerPath(customerId)+'/tickets/'+id;
  if(ticket.status!=='CLOSED')side.append(editor('Reply','Messages are visible to the customer and support staff.',[{name:'message',label:'Message',type:'textarea',required:true,max:4000}],'Send reply',async data=>{await api(path+'/messages','POST',{...data,version:ticket.version});await ticketDetail(customerId,id);}));
  else side.append(empty('This ticket is closed.'));
  if(session.staff&&ticket.status!=='CLOSED'){
    const reps=await api('/api/sales-representatives');
    side.append(editor('Ticket handling','Use the current ticket version when updating status or responsibility.',[
      {name:'status',label:'Status',options:[{value:'',label:'Keep current status'},...forwardStages(ticket.status,['OPEN','IN_PROGRESS','RESOLVED','CLOSED'])]},
      {name:'representativeId',label:'Representative',options:[{value:'',label:'Keep current representative'},...reps.map(rep=>({value:rep.id,label:rep.fullName}))]}
    ],'Update ticket',async data=>{await api(path,'PUT',{status:data.status||null,representativeId:data.representativeId||null,version:ticket.version});await ticketDetail(customerId,id);}));
  }
  paged(messageList,cursor=>api(path+'/messages?limit=20'+(cursor?'&afterId='+cursor:'')),value=>h('article',{class:'record'},h('div',{class:'record-head'},h('h3',{},pretty(value.kind)),badge(value.actorId===customerId?'CUSTOMER':'STAFF')),meta(date(value.occurredAt)),h('p',{},value.body)),value=>value.id);
}
function desk(){nav('desk');const list=h('section');const filter=h('select',{'aria-label':'Filter ticket status'},h('option',{value:''},'All statuses'),['OPEN','IN_PROGRESS','RESOLVED','CLOSED'].map(status=>h('option',{value:status},pretty(status))));
  $('#workspace').replaceChildren(heading('Support desk','Manage customer conversations across the workspace.','TEAM WORKSPACE'),h('div',{class:'card'},h('label',{},'Ticket status',filter)),list);
  function load(){list.replaceChildren();paged(list,cursor=>api('/api/support/tickets?limit=20'+(filter.value?'&status='+filter.value:'')+(cursor?'&afterId='+cursor:'')),ticketCard,value=>value.id);}filter.onchange=load;load();
}
function inbox(){nav('inbox');const list=h('section');$('#workspace').replaceChildren(heading('My inbox','Ticket updates and planned-work notifications. Refresh to check for new items.','YOUR UPDATES'),button('Refresh inbox',inbox,'detail-back'),list);
  paged(list,cursor=>api(customerPath(session.id)+'/notifications?limit=20'+(cursor?'&beforeId='+cursor:'')),value=>{
    const row=h('article',{class:'record'+(value.readAt?' read':'')},h('div',{class:'record-head'},h('h3',{},value.title),badge(value.readAt?'READ':'UNREAD')),meta(date(value.occurredAt)),h('p',{},value.body));
    const actions=h('div',{class:'actions'});
    if(!value.readAt)actions.append(button('Mark read',async()=>{await api(customerPath(session.id)+'/notifications/'+value.id+'/read','PUT');inbox();}));
    if(value.sourceType==='TICKET')actions.append(button('Open ticket',()=>ticketDetail(value.customerId,value.sourceId)));
    row.append(actions);return row;
  },value=>value.id);
}
