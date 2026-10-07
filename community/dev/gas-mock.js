'use strict';
/**
 * 로컬 테스트용 Google Apps Script 흉내(mock).
 *
 * src/Code.gs 를 Node 의 vm 컨텍스트에 올려 실제 배포 없이 서버 코드를 돌립니다.
 * 이 프로젝트가 쓰는 SpreadsheetApp / DriveApp / CacheService / LockService /
 * PropertiesService / Utilities / Session / ScriptApp / HtmlService 기능만 구현했습니다.
 *
 * 실제 Apps Script 처럼 호출(실행)마다 전역 변수가 새로 시작되도록 매번 새 컨텍스트에서
 * Code.gs 를 다시 읽고, 스프레드시트·드라이브·캐시·속성 같은 "서비스 상태"만 공유합니다.
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const crypto = require('crypto');

const CELL_LIMIT = 50000;
const NOT_FOUND = 'No item with the given ID could be found. Possibly because you have not edited this item or you do not have permission to access it.';

const toSigned = (buf) => Array.from(buf, (b) => (b > 127 ? b - 256 : b));
const toBuffer = (bytes) => (Buffer.isBuffer(bytes) ? Buffer.from(bytes) : Buffer.from(Array.from(bytes, (b) => b & 0xff)));
const isDate = (v) => Object.prototype.toString.call(v) === '[object Date]';

function iterator(list) {
  let i = 0;
  return { hasNext: () => i < list.length, next: () => list[i++] };
}

/* ───────────── Blob ───────────── */

class MockBlob {
  constructor(data, contentType, name) {
    this._bytes = typeof data === 'string' ? Buffer.from(data, 'utf8') : toBuffer(data || []);
    this._type = contentType || 'application/octet-stream';
    this._name = name || null;
  }
  getBytes() { return toSigned(this._bytes); }
  getContentType() { return this._type; }
  setContentType(t) { this._type = t; return this; }
  getName() { return this._name; }
  setName(n) { this._name = n; return this; }
  getDataAsString() { return this._bytes.toString('utf8'); }
  copyBlob() { return new MockBlob(this._bytes, this._type, this._name); }
}

/* ───────────── Spreadsheet ───────────── */

/** 스프레드시트에 값을 넣을 때의 자동 변환(수식·숫자·날짜·불리언)을 흉내 냅니다. */
function convertInput(v) {
  if (v === null || v === undefined) return '';
  if (typeof v === 'number') {
    if (!Number.isFinite(v)) throw new Error('Invalid number value: ' + v);
    return v;
  }
  if (typeof v === 'boolean') return v;
  if (isDate(v)) return new Date(v.getTime());
  if (typeof v !== 'string') throw new Error('[mock] Sheets cannot store a ' + typeof v + ' value: ' + String(v));
  if (v.length > CELL_LIMIT) throw new Error('Your input contains more than the maximum of 50000 characters in a single cell.');
  if (v === '') return '';
  if (v[0] === "'") return v.slice(1);
  if (v[0] === '=' && v.length > 1) return { formula: v };
  const t = v.trim();
  if (/^[+-]?(\d+\.?\d*|\.\d+)(e[+-]?\d+)?$/i.test(t)) return Number(t);
  if (/^[+-]?\d{1,3}(,\d{3})+(\.\d+)?$/.test(t)) return Number(t.replace(/,/g, ''));
  if (/^[+-]?\d+(\.\d+)?%$/.test(t)) return Number(t.slice(0, -1)) / 100;
  if (/^(true|false)$/i.test(t)) return t.toLowerCase() === 'true';
  if (/^\d{4}[-/.]\d{1,2}[-/.]\d{1,2}$/.test(t) || /^\d{1,2}\/\d{1,2}(\/\d{2,4})?$/.test(t)) {
    const d = new Date(t.replace(/\./g, '-'));
    return isNaN(d.getTime()) ? v : d;
  }
  return v;
}

function outputValue(v) {
  if (v && typeof v === 'object' && v.formula) return '#FORMULA(' + v.formula + ')';
  if (isDate(v)) return new Date(v.getTime());
  return v;
}

function displayValue(v) {
  if (v === '' || v === null || v === undefined) return '';
  if (typeof v === 'boolean') return v ? 'TRUE' : 'FALSE';
  if (typeof v === 'number') return String(v);
  if (isDate(v)) return v.toISOString();
  if (typeof v === 'object' && v.formula) return '#FORMULA';
  return String(v);
}

class MockTextFinder {
  constructor(range, text) {
    this._range = range;
    this._text = String(text);
    this._case = false;
    this._entire = false;
    this._regex = false;
    this._pos = -1;
  }
  matchCase(b) { this._case = !!b; return this; }
  matchEntireCell(b) { this._entire = !!b; return this; }
  useRegularExpression(b) { this._regex = !!b; return this; }
  ignoreDiacritics() { return this; }
  matchFormulaText() { return this; }
  _cells() {
    const r = this._range;
    const out = [];
    for (let i = 0; i < r._numRows; i++) for (let j = 0; j < r._numCols; j++) out.push([r._row + i, r._col + j]);
    return out;
  }
  _match(v) {
    let hay = displayValue(v);
    let needle = this._text;
    if (this._regex) {
      const re = new RegExp(this._entire ? '^(?:' + needle + ')$' : needle, this._case ? '' : 'i');
      return re.test(hay);
    }
    if (!this._case) {
      hay = hay.toLowerCase();
      needle = needle.toLowerCase();
    }
    return this._entire ? hay === needle : hay.indexOf(needle) >= 0;
  }
  findAll() {
    const sheet = this._range._sheet;
    sheet._ss._env.stats.sheetCalls++;
    return this._cells().filter(([r, c]) => this._match(sheet._read(r, c))).map(([r, c]) => new MockRange(sheet, r, c, 1, 1));
  }
  findNext() {
    const sheet = this._range._sheet;
    sheet._ss._env.stats.sheetCalls++;
    const cells = this._cells();
    for (let k = this._pos + 1; k < cells.length; k++) {
      const [r, c] = cells[k];
      if (this._match(sheet._read(r, c))) {
        this._pos = k;
        return new MockRange(sheet, r, c, 1, 1);
      }
    }
    return null;
  }
}

class MockRange {
  constructor(sheet, row, col, numRows, numCols) {
    this._sheet = sheet;
    this._row = row;
    this._col = col;
    this._numRows = numRows;
    this._numCols = numCols;
  }
  getRow() { return this._row; }
  getColumn() { return this._col; }
  getNumRows() { return this._numRows; }
  getNumColumns() { return this._numCols; }
  getLastRow() { return this._row + this._numRows - 1; }
  getLastColumn() { return this._col + this._numCols - 1; }
  getSheet() { return this._sheet; }
  getA1Notation() {
    const col = (n) => { let s = ''; while (n > 0) { const m = (n - 1) % 26; s = String.fromCharCode(65 + m) + s; n = Math.floor((n - 1) / 26); } return s; };
    return col(this._col) + this._row + (this._numRows > 1 || this._numCols > 1 ? ':' + col(this.getLastColumn()) + this.getLastRow() : '');
  }
  getValues() {
    this._sheet._ss._env.stats.sheetCalls++;
    const out = [];
    for (let i = 0; i < this._numRows; i++) {
      const row = [];
      for (let j = 0; j < this._numCols; j++) row.push(outputValue(this._sheet._read(this._row + i, this._col + j)));
      out.push(row);
    }
    return out;
  }
  getValue() {
    this._sheet._ss._env.stats.sheetCalls++;
    return outputValue(this._sheet._read(this._row, this._col));
  }
  getDisplayValues() {
    return this.getValues().map((r) => r.map(displayValue));
  }
  setValues(values) {
    this._sheet._ss._env.stats.sheetCalls++;
    if (!Array.isArray(values) || values.length !== this._numRows) {
      throw new Error('The number of rows in the data does not match the number of rows in the range. The data has ' +
        (Array.isArray(values) ? values.length : 0) + ' but the range has ' + this._numRows + '.');
    }
    values.forEach((r) => {
      if (!Array.isArray(r) || r.length !== this._numCols) {
        throw new Error('The number of columns in the data does not match the number of columns in the range. The data has ' +
          (Array.isArray(r) ? r.length : 0) + ' but the range has ' + this._numCols + '.');
      }
    });
    this._sheet._write(this._row, this._col, values);
    return this;
  }
  setValue(v) {
    this._sheet._ss._env.stats.sheetCalls++;
    const rows = [];
    for (let i = 0; i < this._numRows; i++) rows.push(new Array(this._numCols).fill(v));
    this._sheet._write(this._row, this._col, rows);
    return this;
  }
  setFontWeight() { return this; }
  setBackground() { return this; }
  setNumberFormat() { return this; }
  setWrap() { return this; }
  setHorizontalAlignment() { return this; }
  createTextFinder(text) { return new MockTextFinder(this, text); }
}

class MockSheet {
  constructor(ss, name) {
    this._ss = ss;
    this._name = name;
    this._rows = [];
    this._frozen = 0;
  }
  getName() { return this._name; }
  getParent() { return this._ss; }
  getLastRow() {
    for (let i = this._rows.length - 1; i >= 0; i--) {
      const r = this._rows[i];
      if (r && r.some((v) => v !== '' && v !== null && v !== undefined)) return i + 1;
    }
    return 0;
  }
  getLastColumn() {
    let m = 0;
    this._rows.forEach((r) => {
      for (let j = (r || []).length - 1; j >= 0; j--) {
        if (r[j] !== '' && r[j] !== null && r[j] !== undefined) { m = Math.max(m, j + 1); break; }
      }
    });
    return m;
  }
  getMaxRows() { return Math.max(1000, this._rows.length); }
  getMaxColumns() { return 26; }
  getRange(a, b, c, d) {
    if (typeof a === 'string') return this._a1(a);
    if (c === undefined) c = 1;
    if (d === undefined) d = 1;
    [a, b, c, d].forEach((n) => {
      if (typeof n !== 'number' || !Number.isInteger(n)) throw new Error('Exception: Invalid argument: ' + n);
    });
    if (a < 1 || b < 1) throw new Error('Exception: Those rows are out of bounds.');
    if (c < 1) throw new Error('Exception: The number of rows in the range must be at least 1.');
    if (d < 1) throw new Error('Exception: The number of columns in the range must be at least 1.');
    return new MockRange(this, a, b, c, d);
  }
  appendRow(values) {
    this._ss._env.stats.sheetCalls++;
    if (!Array.isArray(values)) throw new Error('Exception: appendRow expects an array');
    this._write(this.getLastRow() + 1, 1, [values]);
    return this;
  }
  deleteRow(n) {
    this._ss._env.stats.sheetCalls++;
    this._rows.splice(n - 1, 1);
    return this;
  }
  setFrozenRows(n) { this._frozen = n; return this; }
  getFrozenRows() { return this._frozen; }
  autoResizeColumns() { return this; }
  setColumnWidth() { return this; }
  _read(r, c) {
    const row = this._rows[r - 1];
    const v = row ? row[c - 1] : undefined;
    return v === undefined || v === null ? '' : v;
  }
  _write(r, c, values) {
    const converted = values.map((rowVals) => rowVals.map(convertInput)); // 먼저 전부 검사(실패 시 아무것도 안 씀)
    converted.forEach((rowVals, i) => {
      const ri = r - 1 + i;
      for (let k = this._rows.length; k <= ri; k++) this._rows[k] = [];
      rowVals.forEach((v, j) => { this._rows[ri][c - 1 + j] = v; });
    });
  }
  _a1(a1) {
    const colNum = (s) => s.split('').reduce((n, ch) => n * 26 + (ch.charCodeAt(0) - 64), 0);
    const m = /^([A-Z]+)(\d*)(?::([A-Z]+)(\d*))?$/.exec(String(a1).toUpperCase());
    if (!m) throw new Error('Exception: Range not found');
    const c1 = colNum(m[1]);
    const r1 = m[2] ? Number(m[2]) : 1;
    const c2 = m[3] ? colNum(m[3]) : c1;
    const r2 = m[4] ? Number(m[4]) : (m[2] ? r1 : this.getMaxRows());
    return new MockRange(this, r1, c1, r2 - r1 + 1, c2 - c1 + 1);
  }
}

class MockSpreadsheet {
  constructor(env, id, name) {
    this._env = env;
    this._id = id;
    this._name = name;
    this._sheets = [new MockSheet(this, 'Sheet1')];
  }
  getId() { return this._id; }
  getName() { return this._name; }
  getUrl() { return 'https://docs.google.com/spreadsheets/d/' + this._id + '/edit'; }
  getSheetByName(name) { return this._sheets.find((s) => s._name === name) || null; }
  getSheets() { return this._sheets.slice(); }
  insertSheet(name) {
    if (this.getSheetByName(name)) throw new Error('Exception: A sheet with the name "' + name + '" already exists. Please enter another name.');
    const s = new MockSheet(this, name);
    this._sheets.push(s);
    return s;
  }
  deleteSheet(sheet) {
    if (this._sheets.length <= 1) throw new Error("Exception: You can't remove all the sheets in a document.");
    this._sheets = this._sheets.filter((s) => s !== sheet);
  }
}

/* ───────────── Drive ───────────── */

class MockDriveFile {
  constructor(env, id, name, blob, parent) {
    this._env = env;
    this._id = id;
    this._name = name;
    this._blob = blob;
    this._parents = parent ? [parent] : [];
    this._trashed = false;
    this._access = 'PRIVATE';
    this._permission = 'NONE';
    this._created = Date.now();
  }
  getId() { return this._id; }
  getName() { return this._name; }
  getMimeType() { return this._blob ? this._blob.getContentType() : 'application/vnd.google-apps.spreadsheet'; }
  getSize() { return this._blob ? this._blob._bytes.length : 0; }
  getBlob() {
    if (!this._blob) throw new Error('[mock] this file has no blob');
    return this._blob.copyBlob();
  }
  setSharing(access, permission) {
    if (this._env.options.sharingFails) throw new Error('Exception: Action not allowed');
    this._access = access;
    this._permission = permission;
    return this;
  }
  getSharingAccess() { return this._access; }
  getSharingPermission() { return this._permission; }
  setTrashed(b) { this._trashed = !!b; return this; }
  isTrashed() { return this._trashed; }
  getParents() { return iterator(this._parents.slice()); }
  moveTo(folder) { this._parents = [folder]; return this; }
  getUrl() { return 'https://drive.google.com/file/d/' + this._id + '/view'; }
  getDateCreated() { return new Date(this._created); }
}

class MockFolder {
  constructor(env, id, name, parent) {
    this._env = env;
    this._id = id;
    this._name = name;
    this._parents = parent ? [parent] : [];
    this._trashed = false;
  }
  getId() { return this._id; }
  getName() { return this._name; }
  getUrl() { return 'https://drive.google.com/drive/folders/' + this._id; }
  isTrashed() { return this._trashed; }
  setTrashed(b) { this._trashed = !!b; return this; }
  getParents() { return iterator(this._parents.slice()); }
  createFile(blobOrName, content, mimeType) {
    const blob = blobOrName instanceof MockBlob ? blobOrName.copyBlob() : new MockBlob(content || '', mimeType || 'text/plain', blobOrName);
    const file = new MockDriveFile(this._env, this._env.newId(), blob.getName() || 'Untitled', blob, this);
    this._env.drive.files.set(file._id, file);
    return file;
  }
  createFolder(name) {
    const f = new MockFolder(this._env, this._env.newId(), name, this);
    this._env.drive.folders.set(f._id, f);
    return f;
  }
  getFiles() {
    return iterator(Array.from(this._env.drive.files.values()).filter((f) => !f._trashed && f._parents.indexOf(this) >= 0));
  }
}

/* ───────────── Cache / Properties ───────────── */

class MockCache {
  constructor(env) {
    this._env = env;
    this._m = new Map();
  }
  _key(k) {
    if (typeof k !== 'string') throw new Error('Exception: Invalid argument: key');
    if (k.length > 250) throw new Error('Exception: Argument too large: key');
    return k;
  }
  get(k) {
    this._key(k);
    const e = this._m.get(k);
    if (!e) return null;
    if (e.exp <= this._env.now()) {
      this._m.delete(k);
      return null;
    }
    return e.v;
  }
  put(k, v, ttl) {
    this._key(k);
    v = String(v);
    if (Buffer.byteLength(v, 'utf8') > 100 * 1024) throw new Error('Exception: Argument too large: value');
    let seconds = ttl === undefined ? 600 : Number(ttl);
    if (!Number.isInteger(seconds) || seconds < 1) throw new Error('Exception: Invalid argument: expirationInSeconds');
    if (seconds > 21600) seconds = 21600;
    this._m.set(k, { v: v, exp: this._env.now() + seconds * 1000 });
  }
  remove(k) { this._key(k); this._m.delete(k); }
  getAll(keys) {
    const out = {};
    keys.forEach((k) => { const v = this.get(k); if (v !== null) out[k] = v; });
    return out;
  }
  putAll(obj, ttl) { Object.keys(obj).forEach((k) => this.put(k, obj[k], ttl)); }
  removeAll(keys) { keys.forEach((k) => this.remove(k)); }
}

class MockProperties {
  constructor(env) {
    this._env = env;
    this._m = {};
  }
  getProperty(k) { this._env.stats.propReads++; return Object.prototype.hasOwnProperty.call(this._m, k) ? this._m[k] : null; }
  getProperties() { this._env.stats.propReads++; return Object.assign({}, this._m); }
  getKeys() { this._env.stats.propReads++; return Object.keys(this._m); }
  setProperty(k, v) { this._env.stats.propWrites++; this._m[k] = String(v); return this; }
  setProperties(obj, deleteAllOthers) {
    this._env.stats.propWrites++;
    if (deleteAllOthers) this._m = {};
    Object.keys(obj).forEach((k) => { this._m[k] = String(obj[k]); });
    return this;
  }
  deleteProperty(k) { this._env.stats.propWrites++; delete this._m[k]; return this; }
  deleteAllProperties() { this._env.stats.propWrites++; this._m = {}; return this; }
}

/* ───────────── Utilities.formatDate (자주 쓰는 패턴만) ───────────── */

function formatDate(date, tz, pattern) {
  const d = new Date(date.getTime());
  const parts = {};
  new Intl.DateTimeFormat('en-US', {
    timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }).formatToParts(d).forEach((p) => { parts[p.type] = p.value; });
  const wall = Date.UTC(Number(parts.year), Number(parts.month) - 1, Number(parts.day), Number(parts.hour) % 24, Number(parts.minute), Number(parts.second));
  const offMin = Math.round((wall - Math.floor(d.getTime() / 1000) * 1000) / 60000);
  const sign = offMin < 0 ? '-' : '+';
  const abs = Math.abs(offMin);
  const z = sign + String(Math.floor(abs / 60)).padStart(2, '0') + String(abs % 60).padStart(2, '0');
  const map = {
    yyyy: parts.year, yy: parts.year.slice(2), MM: parts.month, dd: parts.day,
    HH: String(Number(parts.hour) % 24).padStart(2, '0'), mm: parts.minute, ss: parts.second, Z: z,
  };
  return pattern.replace(/yyyy|yy|MM|dd|HH|mm|ss|Z/g, (t) => map[t]);
}

/* ───────────── HtmlService ───────────── */

function compileTemplate(src) {
  let code = 'var __o = [];\n';
  let last = 0;
  const re = /<\?(!=|=)?([\s\S]*?)\?>/g;
  let m;
  while ((m = re.exec(src))) {
    code += '__o.push(' + JSON.stringify(src.slice(last, m.index)) + ');\n';
    if (m[1] === '!=') code += '__o.push(String(' + m[2] + '));\n';
    else if (m[1] === '=') code += '__o.push(__esc(String(' + m[2] + ')));\n';
    else code += m[2] + '\n';
    last = re.lastIndex;
  }
  code += '__o.push(' + JSON.stringify(src.slice(last)) + ');\nreturn __o.join("");';
  return code;
}

const escapeHtml = (s) => s.replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch]);

class MockHtmlOutput {
  constructor(content) {
    this._content = content || '';
    this._title = '';
    this._metas = [];
  }
  getContent() { return this._content; }
  setContent(c) { this._content = c; return this; }
  append(c) { this._content += c; return this; }
  setTitle(t) { this._title = String(t); return this; }
  getTitle() { return this._title; }
  addMetaTag(name, content) {
    const allowed = ['apple-mobile-web-app-capable', 'google-site-verification', 'mobile-web-app-capable', 'viewport'];
    if (allowed.indexOf(name) < 0) throw new Error('Exception: Meta tag name "' + name + '" is not allowed.');
    this._metas.push({ name: name, content: content });
    return this;
  }
  getMetaTags() { return this._metas.slice(); }
  setXFrameOptionsMode(m) { this._xfo = m; return this; }
  setFaviconUrl(u) { this._favicon = u; return this; }
  setSandboxMode() { return this; }
  setWidth() { return this; }
  setHeight() { return this; }
}

/* ───────────── 환경 ───────────── */

function createGasEnv(options) {
  options = Object.assign({ srcDir: path.join(__dirname, '..', 'src') }, options || {});
  const env = {
    options: options,
    ownerEmail: options.ownerEmail || 'owner@example.com',
    activeUser: '',
    clockOffset: 0,
    logs: [],
    triggers: [],
    stats: { propReads: 0, propWrites: 0, sheetCalls: 0, flushes: 0, executions: 0 },
    sheets: new Map(),
    drive: { files: new Map(), folders: new Map(), root: null },
    now: () => Date.now() + env.clockOffset,
    newId: () => '1' + crypto.randomBytes(24).toString('base64url').slice(0, 32),
    lockBusy: false,
  };
  env.drive.root = new MockFolder(env, 'root', '내 드라이브', null);
  env.cache = new MockCache(env);
  env.props = new MockProperties(env);
  let lockHeld = false;
  const lock = {
    tryLock() { if (env.lockBusy) return false; lockHeld = true; return true; },
    waitLock() { if (env.lockBusy) throw new Error('Exception: Lock timeout'); lockHeld = true; },
    releaseLock() { lockHeld = false; },
    hasLock() { return lockHeld; },
  };
  const log = (level) => function () {
    const line = Array.prototype.map.call(arguments, (a) => (typeof a === 'string' ? a : (a && a.stack) || JSON.stringify(a))).join(' ');
    env.logs.push({ level: level, line: line });
    if (options.verbose || (options.printErrors && level === 'error')) console.log('[gas:' + level + '] ' + line);
  };

  const services = {
    SpreadsheetApp: {
      create(name) {
        const id = env.newId();
        const ss = new MockSpreadsheet(env, id, name);
        env.sheets.set(id, ss);
        env.drive.files.set(id, new MockDriveFile(env, id, name, null, env.drive.root));
        return ss;
      },
      openById(id) {
        const ss = env.sheets.get(id);
        const file = env.drive.files.get(id);
        if (!ss || (file && file._trashed && options.trashedIsMissing)) throw new Error('Exception: Unexpected error while getting the method or property openById on object SpreadsheetApp.');
        return ss;
      },
      getActiveSpreadsheet() { return null; },
      flush() { env.stats.flushes++; },
    },
    DriveApp: {
      Access: { ANYONE: 'ANYONE', ANYONE_WITH_LINK: 'ANYONE_WITH_LINK', DOMAIN: 'DOMAIN', DOMAIN_WITH_LINK: 'DOMAIN_WITH_LINK', PRIVATE: 'PRIVATE' },
      Permission: { VIEW: 'VIEW', EDIT: 'EDIT', COMMENT: 'COMMENT', OWNER: 'OWNER', ORGANIZER: 'ORGANIZER', NONE: 'NONE' },
      createFolder(name) { return env.drive.root.createFolder(name); },
      getRootFolder() { return env.drive.root; },
      getFolderById(id) {
        const f = env.drive.folders.get(id);
        if (!f) throw new Error('Exception: ' + NOT_FOUND);
        return f;
      },
      getFileById(id) {
        const f = env.drive.files.get(id);
        if (!f) throw new Error('Exception: ' + NOT_FOUND);
        return f;
      },
    },
    CacheService: {
      getScriptCache: () => env.cache,
      getUserCache: () => env.cache,
      getDocumentCache: () => null,
    },
    LockService: {
      getScriptLock: () => lock,
      getUserLock: () => lock,
      getDocumentLock: () => null,
    },
    PropertiesService: {
      getScriptProperties: () => env.props,
      getUserProperties: () => env.props,
      getDocumentProperties: () => null,
    },
    Utilities: {
      DigestAlgorithm: { MD2: 'md2', MD5: 'md5', SHA_1: 'sha1', SHA_256: 'sha256', SHA_384: 'sha384', SHA_512: 'sha512' },
      Charset: { US_ASCII: 'ascii', UTF_8: 'utf8' },
      computeDigest(alg, value) {
        const hash = crypto.createHash(alg);
        hash.update(typeof value === 'string' ? Buffer.from(value, 'utf8') : toBuffer(value));
        return toSigned(hash.digest());
      },
      base64Encode(data) { return (typeof data === 'string' ? Buffer.from(data, 'utf8') : toBuffer(data)).toString('base64'); },
      base64EncodeWebSafe(data) { return (typeof data === 'string' ? Buffer.from(data, 'utf8') : toBuffer(data)).toString('base64url'); },
      base64Decode(str) {
        const s = String(str).replace(/\s/g, '');
        if (!/^[A-Za-z0-9+/]*={0,2}$/.test(s) || s.length % 4 === 1) throw new Error('Exception: Could not decode string.');
        return toSigned(Buffer.from(s, 'base64'));
      },
      base64DecodeWebSafe(str) { return toSigned(Buffer.from(String(str), 'base64url')); },
      newBlob(data, contentType, name) { return new MockBlob(data, contentType, name); },
      getUuid() { return crypto.randomUUID(); },
      formatDate: formatDate,
      sleep() {},
    },
    Session: {
      getActiveUser: () => ({ getEmail: () => env.activeUser || '' }),
      getEffectiveUser: () => ({ getEmail: () => env.ownerEmail }),
      getScriptTimeZone: () => 'Asia/Seoul',
      getTemporaryActiveUserKey: () => '',
    },
    ScriptApp: {
      getService: () => ({ getUrl: () => (options.serviceUrl === undefined ? 'https://script.google.com/macros/s/MOCK_DEPLOYMENT/exec' : options.serviceUrl), isEnabled: () => true }),
      getProjectTriggers: () => env.triggers.slice(),
      deleteTrigger: (t) => { env.triggers = env.triggers.filter((x) => x !== t); },
      newTrigger(fn) {
        const spec = { fn: fn };
        const builder = {
          timeBased() { spec.type = 'CLOCK'; return builder; },
          everyDays(n) { spec.everyDays = n; return builder; },
          everyHours(n) { spec.everyHours = n; return builder; },
          everyMinutes(n) { spec.everyMinutes = n; return builder; },
          atHour(hh) { spec.atHour = hh; return builder; },
          create() {
            const t = { spec: spec, getHandlerFunction: () => fn, getEventType: () => spec.type, getUniqueId: () => String(env.triggers.length + 1) };
            env.triggers.push(t);
            return t;
          },
        };
        return builder;
      },
    },
    HtmlService: {
      XFrameOptionsMode: { ALLOWALL: 'ALLOWALL', DEFAULT: 'DEFAULT' },
      SandboxMode: { IFRAME: 'IFRAME', NATIVE: 'NATIVE', EMULATED: 'EMULATED' },
      createHtmlOutput: (html) => new MockHtmlOutput(html),
      createHtmlOutputFromFile: (name) => new MockHtmlOutput(readHtml(name)),
      createTemplateFromFile: (name) => makeTemplate(readHtml(name)),
      createTemplate: (src) => makeTemplate(src),
    },
  };

  let current = null; // 지금 실행 중인 컨텍스트 (템플릿 평가용)

  function readHtml(name) {
    const file = path.join(options.srcDir, name + '.html');
    if (!fs.existsSync(file)) throw new Error('Exception: No HTML file named ' + name + ' was found.');
    return fs.readFileSync(file, 'utf8');
  }

  function makeTemplate(src) {
    const t = {};
    Object.defineProperty(t, 'evaluate', {
      enumerable: false,
      value() {
        const vars = {};
        Object.keys(t).forEach((k) => { vars[k] = t[k]; });
        const fn = vm.runInContext('(function (__vars, __esc) { with (__vars) { ' + compileTemplate(src) + ' } })', current);
        return new MockHtmlOutput(fn(vars, escapeHtml));
      },
    });
    Object.defineProperty(t, 'getRawContent', { enumerable: false, value: () => src });
    return t;
  }

  const code = fs.readFileSync(path.join(options.srcDir, 'Code.gs'), 'utf8');
  const script = new vm.Script(code, { filename: 'Code.gs' });

  /** 테스트에서 설정을 바꾸고 싶을 때: 매 실행마다 Code.gs 다음에 실행할 코드 (예: 'CONFIG.POST_COOLDOWN_SEC = 0;') */
  env.patch = options.patch || '';

  /** 실제 Apps Script 실행 하나처럼: 새 전역에서 Code.gs 를 읽습니다. */
  function freshContext() {
    const ctx = vm.createContext(Object.assign({
      console: { log: log('log'), info: log('info'), warn: log('warn'), error: log('error') },
      Logger: { log: log('log') },
    }, services));
    script.runInContext(ctx);
    if (env.patch) vm.runInContext(env.patch, ctx);
    return ctx;
  }

  function execute(fn, args, publicOnly) {
    const ctx = freshContext();
    if (typeof fn !== 'string' || typeof ctx[fn] !== 'function' || (publicOnly && /_$/.test(fn))) {
      throw new Error('Script function not found: ' + fn);
    }
    current = ctx;
    env.stats.executions++;
    lockHeld = false;
    try {
      return ctx[fn].apply(null, args);
    } finally {
      lockHeld = false; // 실행이 끝나면 잠금은 자동으로 풀립니다.
      current = null;
    }
  }

  /** google.script.run 처럼 호출합니다. (밑줄 함수 금지, 인자/결과 직렬화, Date 반환 금지) */
  env.run = function (fn) {
    const sent = JSON.parse(JSON.stringify(Array.prototype.slice.call(arguments, 1)));
    let value;
    try {
      value = execute(fn, sent, true);
    } catch (e) {
      throw new Error(e && e.message ? e.message : String(e));
    }
    assertSerializable(value, fn + '()');
    return value === undefined ? null : JSON.parse(JSON.stringify(value));
  };

  /** 편집기에서 실행하는 것처럼 호출합니다. (반환값 그대로) */
  env.runAsEditor = function (fn) {
    const before = env.activeUser;
    env.activeUser = env.ownerEmail;
    try {
      return execute(fn, Array.prototype.slice.call(arguments, 1), false);
    } finally {
      env.activeUser = before;
    }
  };

  /** 웹 앱 주소로 접속한 것처럼 doGet 을 실행하고 HtmlOutput 을 돌려줍니다. */
  env.doGet = function (parameter) {
    parameter = parameter || {};
    const parameters = {};
    Object.keys(parameter).forEach((k) => { parameters[k] = [parameter[k]]; });
    return execute('doGet', [{ parameter: parameter, parameters: parameters, queryString: new URLSearchParams(parameter).toString() }], false);
  };

  /** 테스트용: 새 실행 컨텍스트에서 식 하나를 평가합니다. (설정 변경은 env.patch 를 쓰세요) */
  env.evaluate = function (expr) {
    return vm.runInContext(expr, freshContext());
  };

  env.sheet = function (name) {
    const id = env.props._m.SPREADSHEET_ID;
    const ss = id && env.sheets.get(id);
    return ss ? ss.getSheetByName(name) : null;
  };

  /** 시트의 데이터 행(헤더 제외)을 그대로(저장된 값) 돌려줍니다. */
  env.rows = function (name) {
    const sh = env.sheet(name);
    if (!sh) return [];
    const last = sh.getLastRow();
    const out = [];
    for (let r = 2; r <= last; r++) out.push((sh._rows[r - 1] || []).slice());
    return out;
  };

  /* 개발 서버용 저장/복원 */
  env.serialize = function () {
    const encodeCell = (v) => {
      if (isDate(v)) return { $date: v.getTime() };
      if (v && typeof v === 'object' && v.formula) return { $formula: v.formula };
      return v;
    };
    const folders = Array.from(env.drive.folders.values()).map((f) => ({ id: f._id, name: f._name, parent: f._parents[0] ? f._parents[0]._id : 'root', trashed: f._trashed }));
    const files = Array.from(env.drive.files.values()).map((f) => ({
      id: f._id, name: f._name, parent: f._parents[0] ? f._parents[0]._id : 'root', trashed: f._trashed, access: f._access,
      created: f._created, blob: f._blob ? { type: f._blob._type, name: f._blob._name, data: f._blob._bytes.toString('base64') } : null,
    }));
    const sheets = Array.from(env.sheets.values()).map((ss) => ({
      id: ss._id, name: ss._name,
      sheets: ss._sheets.map((s) => ({ name: s._name, frozen: s._frozen, rows: s._rows.map((r) => (r || []).map(encodeCell)) })),
    }));
    const cache = Array.from(env.cache._m.entries());
    return { version: 1, props: env.props._m, folders: folders, files: files, sheets: sheets, cache: cache, triggers: env.triggers.map((t) => t.spec) };
  };

  env.restore = function (data) {
    if (!data || data.version !== 1) return;
    const decodeCell = (v) => {
      if (v && typeof v === 'object' && v.$date !== undefined) return new Date(v.$date);
      if (v && typeof v === 'object' && v.$formula) return { formula: v.$formula };
      return v;
    };
    env.props._m = Object.assign({}, data.props);
    env.drive.folders = new Map();
    env.drive.files = new Map();
    const folderOf = (id) => (id === 'root' ? env.drive.root : env.drive.folders.get(id));
    data.folders.forEach((f) => env.drive.folders.set(f.id, new MockFolder(env, f.id, f.name, null)));
    data.folders.forEach((f) => {
      const folder = env.drive.folders.get(f.id);
      folder._parents = [folderOf(f.parent)].filter(Boolean);
      folder._trashed = !!f.trashed;
    });
    data.files.forEach((f) => {
      const blob = f.blob ? new MockBlob(Buffer.from(f.blob.data, 'base64'), f.blob.type, f.blob.name) : null;
      const file = new MockDriveFile(env, f.id, f.name, blob, folderOf(f.parent));
      file._trashed = !!f.trashed;
      file._access = f.access;
      file._created = f.created;
      env.drive.files.set(f.id, file);
    });
    env.sheets = new Map();
    data.sheets.forEach((s) => {
      const ss = new MockSpreadsheet(env, s.id, s.name);
      ss._sheets = s.sheets.map((sh) => {
        const ms = new MockSheet(ss, sh.name);
        ms._frozen = sh.frozen;
        ms._rows = sh.rows.map((r) => r.map(decodeCell));
        return ms;
      });
      env.sheets.set(s.id, ss);
    });
    env.cache._m = new Map(data.cache || []);
    env.triggers = [];
    (data.triggers || []).forEach((spec) => {
      env.triggers.push({ spec: spec, getHandlerFunction: () => spec.fn, getEventType: () => spec.type, getUniqueId: () => String(env.triggers.length + 1) });
    });
  };

  return env;
}

function assertSerializable(v, where) {
  if (isDate(v)) throw new Error('google.script.run cannot return a Date (' + where + ')');
  if (typeof v === 'function') throw new Error('google.script.run cannot return a function (' + where + ')');
  if (v && typeof v === 'object') Object.keys(v).forEach((k) => assertSerializable(v[k], where + '.' + k));
}

module.exports = { createGasEnv, MockBlob };
