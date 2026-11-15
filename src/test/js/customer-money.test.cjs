const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

test('customer money formatter keeps paise returned by the API', () => {
  const appPath = path.join(__dirname, '../../main/resources/static/app.js');
  const appSource = fs.readFileSync(appPath, 'utf8');
  const moneyDeclaration = appSource.split(/\r?\n/).find(line => line.startsWith('const money ='));

  assert.ok(moneyDeclaration, 'customer app should define its money formatter');
  const formatted = vm.runInNewContext(`${moneyDeclaration}\nmoney(1234.56)`, { Intl, Number });
  assert.match(formatted, /1,234\.56/);
});
