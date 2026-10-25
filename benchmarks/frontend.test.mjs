import test from 'node:test';
import assert from 'node:assert/strict';
import {claims, pretty, forwardStages} from '../src/main/resources/static/ui.js';
test('display helpers preserve text and unknown states do not offer transitions', () => {
  assert.equal(pretty('IN_PROGRESS'), 'In progress');
  assert.deepEqual(forwardStages('OPEN',['OPEN','RESOLVED','CLOSED']),['RESOLVED','CLOSED']);
  assert.deepEqual(forwardStages('CLOSED',['OPEN','RESOLVED','CLOSED']),[]);
  assert.deepEqual(forwardStages('UNKNOWN',['OPEN','RESOLVED','CLOSED']),[]);
});
test('session metadata decodes base64url UTF-8 without treating the payload as proof of authorization', () => {
  const payload={sub:'123',scope:'crm:staff',label:'Raghunandan ✓'};
  const token='header.'+Buffer.from(JSON.stringify(payload)).toString('base64url')+'.signature';
  assert.deepEqual(claims(token),payload);
  assert.throws(()=>claims('invalid'));
});
