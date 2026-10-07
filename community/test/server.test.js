'use strict';
/**
 * 서버(Code.gs) 테스트 — 실제 Apps Script 대신 dev/gas-mock.js 위에서 실행합니다.
 *   node --test test/
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const { createGasEnv } = require('../dev/gas-mock');

const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==';
const GIF = 'R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';
const JPG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x01]).toString('base64');
const A = 'a'.repeat(32);
const B = 'b'.repeat(32);
const C = 'c'.repeat(32);
const NO_COOLDOWN = 'CONFIG.POST_COOLDOWN_SEC = 0; CONFIG.COMMENT_COOLDOWN_SEC = 0;';

function freshEnv(options) {
  const env = createGasEnv(Object.assign({ patch: NO_COOLDOWN }, options || {}));
  env.runAsEditor('setup');
  return env;
}

function post(env, device, extra) {
  return env.run('apiCreatePost', Object.assign({ device: device, g: 'free', title: '제목', content: '내용', nick: '', pw: '1234' }, extra || {}));
}

function upload(env, device, data, extra) {
  return env.run('apiUploadImage', Object.assign({ device: device, data: data === undefined ? PNG : data, w: 1, h: 1 }, extra || {}));
}

function adminToken(env) {
  return env.run('apiAdminLogin', { device: A, key: env.props._m.ADMIN_KEY }).token;
}

function rowOf(env, sheet, id) {
  return env.rows(sheet).find((r) => r[0] === id || r[0] === JSON.stringify(id));
}

/* ───────────── 준비 · 진입점 ───────────── */

test('setup 은 시트·갤러리·안내 공지·정리 트리거를 한 번만 만든다', () => {
  const env = freshEnv();
  const ss = env.sheets.get(env.props._m.SPREADSHEET_ID);
  assert.deepEqual(ss.getSheets().map((s) => s.getName()), ['galleries', 'posts', 'comments', 'images', 'votes', 'bans']);
  assert.equal(env.rows('galleries').length, 4);
  assert.equal(env.rows('posts').length, 1);
  assert.deepEqual(env.triggers.map((t) => t.getHandlerFunction()), ['cleanupOrphanImages']);
  assert.equal(env.triggers[0].spec.everyHours, 6);
  // 안 쓰는 열은 지워서 셀 한도를 아낀다
  assert.equal(env.sheet('votes').getMaxColumns(), 4);
  assert.equal(env.sheet('posts').getMaxColumns(), 23);
  assert.match(env.props._m.ADMIN_KEY, /^[0-9a-f]{4}(-[0-9a-f]{4}){5}$/);
  // 시트는 드라이브 데이터 폴더 안으로 옮겨진다
  assert.equal(env.drive.files.get(ss.getId())._parents[0]._id, env.props._m.FOLDER_ID);

  env.runAsEditor('setup');
  assert.equal(env.rows('galleries').length, 4);
  assert.equal(env.rows('posts').length, 1);
  assert.equal(env.triggers.length, 1);
});

test('브라우저에서 setup 을 불러도 관리자 키가 새어 나가지 않고, 설정 뒤에는 주인만 실행할 수 있다', () => {
  const blank = createGasEnv({ patch: NO_COOLDOWN });
  assert.equal(blank.run('setup'), null, '처음 한 번은 누구나 초기화 가능, 반환값 없음');
  assert.ok(blank.props._m.ADMIN_KEY);
  assert.throws(() => blank.run('setup'), /편집기에서 직접 실행/);
  const env = freshEnv();
  assert.throws(() => env.run('setup'), /편집기에서 직접 실행/);
  // 주인이 직접 만든 빈 시트는 다시 setup 해도 지우지 않는다
  env.sheets.get(env.props._m.SPREADSHEET_ID).insertSheet('내 메모');
  env.runAsEditor('setup');
  assert.ok(env.sheets.get(env.props._m.SPREADSHEET_ID).getSheetByName('내 메모'));
  assert.throws(() => env.run('resetAdminKey'), /편집기에서 직접 실행/);
  assert.throws(() => env.run('fail_', 'x'), /Script function not found/);
  assert.throws(() => env.run('props_'), /Script function not found/);
});

test('doGet 은 부트 데이터·제목·viewport 를 넣고, 주소 파라미터의 </script> 를 무력화한다', () => {
  const env = freshEnv();
  const out = env.doGet({ g: 'free', id: '1', q: '</script><script>alert(1)</script>' });
  assert.equal(out.getTitle(), '모두의 갤러리 이용 안내 - 자유 갤러리');
  assert.deepEqual(out.getMetaTags(), [{ name: 'viewport', content: 'width=device-width, initial-scale=1' }]);
  const html = out.getContent();
  assert.ok(!html.includes('<script>alert(1)'));
  const m = /window\.__BOOT__ = (.*?);<\/script>/.exec(html);
  assert.ok(m, 'boot script exists');
  const boot = JSON.parse(m[1]);
  assert.equal(boot.ok, true);
  assert.equal(boot.params.q, '</script><script>alert(1)</script>');
  assert.equal(boot.galleries.length, 4);
  assert.equal(boot.url, 'https://script.google.com/macros/s/MOCK_DEPLOYMENT/exec');
  assert.ok(html.includes('<style>') && html.includes('google.script.run'));
  assert.equal(boot.owner, false);
});

test('설정이 한 번 끝나면 스크립트 속성을 다시 읽지 않는다 (하루 읽기 제한 대비)', () => {
  const env = freshEnv();
  const before = env.stats.propReads;
  for (let i = 0; i < 5; i++) env.run('apiList', { device: A, g: 'free' });
  assert.equal(env.stats.propReads, before);
});

/* ───────────── 글 ───────────── */

test('글을 쓰면 목록·보기에 나오고, 이미 본 글은 조회수가 오르지 않는다', () => {
  const env = freshEnv();
  const res = post(env, A, { title: '첫 글', content: '안녕하세요', nick: '테스터', head: '정보' });
  assert.deepEqual(res, { id: 2, no: 2, g: 'free' });
  const list = env.run('apiList', { device: A, g: 'free' });
  assert.equal(list.total, 1);
  assert.equal(list.notices.length, 1);
  const p = list.posts[0];
  assert.equal(p.title, '첫 글');
  assert.equal(p.nick, '테스터');
  assert.equal(p.head, '정보');
  assert.match(p.code, /^[0-9a-f]{4}$/);
  assert.equal(p.admin, false);
  assert.equal(p.pwHash, undefined);

  assert.equal(env.run('apiView', { device: A, id: 2 }).post.views, 1);
  assert.equal(env.run('apiView', { device: A, id: 2, seen: true }).post.views, 1); // 브라우저가 6시간 안에 본 글
  assert.equal(env.run('apiView', { device: B, id: 2 }).post.views, 2);
  assert.equal(env.run('apiView', { id: 2 }).post.views, 2); // 식별값 없으면 안 셈
  assert.equal(env.cache._m.size < 20, true, '조회 기록을 서버 캐시에 쌓지 않는다');

  const other = post(env, B, { g: 'photo' });
  assert.equal(other.no, 1); // 갤러리마다 번호가 따로
});

test('스프레드시트 수식·자동 변환 공격이 통하지 않는다', () => {
  const env = freshEnv();
  const evil = {
    title: '=IMPORTXML("http://evil.example","//a")',
    nick: '0001',
    content: 'TRUE\n=1+1\n1/2\n\'quoted\n+82 10\n{"a":1}\n[1,2]',
  };
  const { id } = post(env, A, evil);
  const v = env.run('apiView', { device: A, id: id });
  assert.equal(v.post.title, evil.title);
  assert.equal(v.post.nick, evil.nick);
  assert.equal(v.post.content, evil.content);
  const raw = rowOf(env, 'posts', id);
  raw.forEach((cell) => {
    if (typeof cell === 'string' && cell) assert.ok(/^["[{]/.test(cell), 'string cells are JSON-encoded: ' + cell);
    assert.ok(!(cell && typeof cell === 'object'), 'no formulas stored');
  });
  assert.equal(post(env, A, { title: '1/2' }).id, id + 1);
  assert.equal(env.run('apiView', { device: A, id: id + 1 }).post.title, '1/2');
});

test('입력값 검사: 제목·비밀번호·닉네임·식별값·숨은 필드', () => {
  const env = freshEnv();
  assert.throws(() => post(env, A, { title: '  ' }), /제목을 입력/);
  assert.throws(() => post(env, A, { title: 'x'.repeat(81) }), /80자까지/);
  assert.throws(() => post(env, A, { content: '' }), /내용을 입력/);
  assert.throws(() => post(env, A, { pw: '12' }), /4자 이상/);
  assert.throws(() => post(env, A, { nick: '운 영 자' }), /사용할 수 없는 닉네임/);
  assert.throws(() => post(env, A, { nick: 'ADMIN' }), /사용할 수 없는 닉네임/);
  assert.throws(() => post(env, 'not-a-device'), /브라우저 식별값/);
  assert.throws(() => post(env, A, { hp: 'http://spam' }), /잘못된 요청/);
  assert.throws(() => post(env, A, { g: 'nope' }), /존재하지 않는 갤러리/);
  assert.throws(() => post(env, A, { content: 'x'.repeat(20001) }), /20000자까지/);

  // 제어문자·짝 없는 서로게이트·보이지 않는 문자 제거, 닉네임 기본값, 말머리 기본값
  const { id } = post(env, A, { title: 'a\u0000b\u202Ec\u200B', content: 'x\uD800y\u0007z  \n', nick: '\u3164', head: '없는말머리' });
  const v = env.run('apiView', { device: A, id: id }).post;
  assert.equal(v.title, 'abc');
  assert.equal(v.content, 'xyz');
  assert.equal(v.nick, 'ㅇㅇ');
  assert.equal(v.head, '일반');

  // 큰따옴표 20000자는 JSON 으로 두 배가 되어도 셀 한도(5만 자) 안에 들어간다
  const quotes = post(env, A, { content: '"'.repeat(20000) });
  assert.equal(env.run('apiView', { device: A, id: quotes.id }).post.content.length, 20000);
});

test('도배 방지: 같은 기기는 정해진 시간 안에 다시 글을 못 쓴다', () => {
  const env = freshEnv({ patch: '' });
  post(env, A);
  assert.throws(() => post(env, A), /20초에 한 번만/);
  post(env, B);
  env.clockOffset += 21 * 1000;
  post(env, A);
});

/* ───────────── 사진 ───────────── */

test('사진을 올리고 글에 붙이면 공개 링크·썸네일·원본 데이터가 맞다', () => {
  const env = freshEnv();
  const img = upload(env, A, PNG, { w: 640, h: 480 });
  assert.equal(img.mime, 'image/png');
  assert.equal(img.pub, false, '글에 붙기 전에는 비공개');
  assert.equal(img.w, 640);
  const file = env.drive.files.get(img.id);
  assert.equal(file._access, 'PRIVATE');
  assert.equal(file._parents[0]._id, env.props._m.IMAGE_FOLDER_ID);
  assert.equal(rowOf(env, 'images', img.id)[1], 0);
  assert.throws(() => env.run('apiImage', { id: img.id }), /글에 첨부되지 않은 사진/);

  const gif = upload(env, A, GIF);
  assert.equal(gif.mime, 'image/gif');
  const { id } = post(env, A, { content: '[사진1]\n글\n[사진2]', images: [img.id, gif.id] });
  const v = env.run('apiView', { device: B, id: id }).post;
  assert.equal(v.images.length, 2);
  assert.equal(v.img, 2);
  assert.equal(v.thumb, img.id); // GIF는 썸네일로 안 씀
  assert.deepEqual(v.images.map((x) => x.pub), [true, true]);
  assert.equal(file._access, 'ANYONE_WITH_LINK', '글에 붙으면 공개');
  assert.equal(rowOf(env, 'images', img.id)[1], id);
  assert.equal(rowOf(env, 'images', img.id)[7], true);

  assert.equal(env.run('apiImage', { id: img.id }).data, PNG);
  assert.equal(env.run('apiImage', { id: gif.id }).mime, 'image/gif');

  const list = env.run('apiList', { device: A, g: 'free' });
  assert.equal(list.posts[0].img, 2);
});

test('사진 업로드 검사: 이미지가 아니거나 너무 크면 거절', () => {
  const env = freshEnv();
  assert.throws(() => upload(env, A, Buffer.from('<html>not an image</html>').toString('base64')), /jpg, png, gif, webp/);
  assert.throws(() => upload(env, A, '!!!'), /올바르지 않습니다/);
  assert.throws(() => upload(env, A, ''), /사진 데이터가 없습니다/);
  assert.equal(upload(env, A, JPG).mime, 'image/jpeg');
  env.patch = NO_COOLDOWN + ' CONFIG.MAX_IMAGE_MB = 0.00005;';
  assert.throws(() => upload(env, A, PNG), /MB까지/);
});

test('남의 사진·다른 글의 사진·앱 밖의 드라이브 파일은 쓸 수 없다', () => {
  const env = freshEnv();
  const mine = upload(env, A);
  assert.throws(() => post(env, B, { images: [mine.id] }), /직접 올린 사진만/);
  const { id } = post(env, A, { images: [mine.id] });
  assert.throws(() => post(env, A, { images: [mine.id] }), /다른 글에 쓰인 사진/);
  assert.throws(() => post(env, A, { images: ['1' + 'x'.repeat(30)] }), /사진을 찾을 수 없습니다/);
  assert.throws(() => post(env, A, { images: 'abc' }), /사진 정보가 올바르지 않습니다/);
  assert.throws(() => post(env, A, { images: ['../etc'] }), /사진 정보가 올바르지 않습니다/);
  // 데이터 스프레드시트도 드라이브 파일이지만, 사진 목록에 없으므로 내보내지 않는다
  assert.throws(() => env.run('apiImage', { id: env.props._m.SPREADSHEET_ID }), /사진을 찾을 수 없습니다/);
  assert.ok(id > 0);
  const many = [];
  for (let i = 0; i < 21; i++) many.push(upload(env, A).id);
  assert.throws(() => post(env, A, { images: many }), /20장까지/);
});

test('공유가 막힌 계정에서도 사진은 서버를 거쳐 보인다', () => {
  const env = freshEnv({ sharingFails: true });
  const img = upload(env, A);
  assert.equal(img.pub, false);
  const { id } = post(env, A, { images: [img.id] });
  const v = env.run('apiView', { device: A, id: id }).post;
  assert.equal(v.thumb, '');
  assert.equal(v.images[0].pub, false);
  assert.equal(env.run('apiImage', { id: img.id }).data, PNG);
});

test('하루 지난 미첨부 사진은 정리되고, 글에 붙은 사진은 남는다', () => {
  const env = freshEnv();
  const orphan = upload(env, A);
  const fresh = upload(env, A);
  const used = upload(env, A);
  post(env, A, { images: [used.id] });
  const sh = env.sheet('images');
  const createdCol = 9;
  [orphan.id, used.id].forEach((id) => {
    const r = sh._rows.findIndex((row) => row[0] === JSON.stringify(id));
    sh._rows[r][createdCol - 1] = Date.now() - 2 * 24 * 3600 * 1000;
  });
  env.runAsEditor('cleanupOrphanImages');
  assert.equal(env.drive.files.get(orphan.id)._trashed, true);
  assert.equal(rowOf(env, 'images', orphan.id)[9], true);
  assert.equal(env.drive.files.get(fresh.id)._trashed, false);
  assert.equal(env.drive.files.get(used.id)._trashed, false);
  assert.throws(() => env.run('apiImage', { id: orphan.id }), /삭제된 사진/);
});

/* ───────────── 추천 ───────────── */

test('추천/비추천은 기기당 한 번, 자기 글은 불가, 기준을 넘으면 개념글', () => {
  const env = freshEnv();
  const token = adminToken(env);
  env.run('apiAdminSaveGallery', { device: A, admin: token, gallery: { id: 'free', name: '자유', desc: '', heads: '일반,정보', threshold: 2, order: 1 } });
  const { id } = post(env, A);
  assert.throws(() => env.run('apiVote', { device: A, id: id, type: 'up' }), /자기 글/);
  assert.deepEqual(env.run('apiVote', { device: B, id: id, type: 'up' }), { up: 1, down: 0 });
  assert.throws(() => env.run('apiVote', { device: B, id: id, type: 'down' }), /이미 추천 또는 비추천/);
  assert.equal(env.run('apiList', { device: A, g: 'free', mode: 'concept' }).total, 0);
  assert.deepEqual(env.run('apiVote', { device: C, id: id, type: 'up' }), { up: 2, down: 0 });
  assert.deepEqual(env.run('apiVote', { device: 'd'.repeat(32), id: id, type: 'down' }), { up: 2, down: 1 });
  const concept = env.run('apiList', { device: A, g: 'free', mode: 'concept' });
  assert.equal(concept.total, 1);
  assert.equal(concept.posts[0].up, 2);
  const home = env.run('apiHome', { device: A });
  assert.equal(home.best[0].id, id);
  assert.equal(home.concept[0].id, id);
});

/* ───────────── 댓글 ───────────── */

test('댓글·답글을 달고 비밀번호로 지운다 (답글이 있으면 "삭제된 댓글" 자리 유지)', () => {
  const env = freshEnv();
  const { id } = post(env, A);
  const c1 = env.run('apiAddComment', { device: B, postId: id, nick: '댓글러', pw: '1111', content: '첫 댓글 https://example.com' });
  assert.equal(c1.count, 1);
  const top = c1.comments[0];
  const r1 = env.run('apiAddComment', { device: C, postId: id, parentId: top.id, pw: '2222', content: '답글' });
  const reply = r1.comments.find((c) => c.parent === top.id);
  // 답글에 단 답글은 원댓글 아래로 모인다
  const r2 = env.run('apiAddComment', { device: A, postId: id, parentId: reply.id, pw: '3333', content: '답글의 답글' });
  assert.equal(r2.count, 3);
  assert.deepEqual(r2.comments.map((c) => c.parent), [0, top.id, top.id]);
  assert.equal(env.run('apiList', { device: A, g: 'free' }).posts[0].cmt, 3);

  assert.throws(() => env.run('apiDeleteComment', { device: B, id: top.id, pw: '0000' }), /비밀번호가 맞지 않습니다/);
  const d = env.run('apiDeleteComment', { device: B, id: top.id, pw: '1111' });
  assert.equal(d.count, 2);
  assert.equal(d.comments[0].deleted, true);
  assert.equal(d.comments[0].content, '');
  assert.equal(d.comments[0].nick, '');
  assert.throws(() => env.run('apiAddComment', { device: B, postId: id, parentId: top.id, pw: '1111', content: 'x' }), /삭제된 댓글에는/);
  assert.throws(() => env.run('apiDeleteComment', { device: B, id: top.id, pw: '1111' }), /이미 삭제/);
  assert.equal(env.run('apiList', { device: A, g: 'free' }).posts[0].cmt, 2);

  env.run('apiDeleteComment', { device: C, id: reply.id, pw: '2222' });
  const last = env.run('apiDeleteComment', { device: A, id: r2.comments[2].id, pw: '3333' });
  assert.deepEqual(last.comments, []); // 답글이 다 지워지면 원댓글 자리도 사라짐
  assert.throws(() => env.run('apiAddComment', { device: B, postId: id, pw: '1', content: 'x' }), /4자 이상/);
  assert.throws(() => env.run('apiAddComment', { device: B, postId: id, pw: '1234', content: ' ' }), /댓글 내용을 입력/);
  assert.throws(() => env.run('apiAddComment', { device: B, postId: 999, pw: '1234', content: 'x' }), /존재하지 않는 글/);
});

test('댓글 도배 방지', () => {
  const env = freshEnv({ patch: 'CONFIG.POST_COOLDOWN_SEC = 0;' });
  const { id } = post(env, A);
  env.run('apiAddComment', { device: B, postId: id, pw: '1234', content: '1' });
  assert.throws(() => env.run('apiAddComment', { device: B, postId: id, pw: '1234', content: '2' }), /5초에 한 번만/);
  env.clockOffset += 6000;
  env.run('apiAddComment', { device: B, postId: id, pw: '1234', content: '3' });
});

/* ───────────── 수정 · 삭제 ───────────── */

test('비밀번호로 글을 고치고, 뺀 사진은 휴지통으로 간다', () => {
  const env = freshEnv();
  const keep = upload(env, A);
  const drop = upload(env, A);
  const { id } = post(env, A, { images: [keep.id, drop.id], pw: 'secret' });
  assert.throws(() => env.run('apiPostForEdit', { device: A, id: id, pw: 'wrong' }), /비밀번호가 맞지 않습니다/);
  const edit = env.run('apiPostForEdit', { device: B, id: id, pw: 'secret' });
  assert.equal(edit.post.images.length, 2);
  assert.equal(edit.gallery.id, 'free');

  const added = upload(env, B); // 다른 기기에서 고치면서 새로 올린 사진
  env.run('apiUpdatePost', { device: B, id: id, pw: 'secret', title: '고친 제목', content: '[사진2] 고친 내용', head: '정보', images: [keep.id, added.id] });
  const v = env.run('apiView', { device: A, id: id }).post;
  assert.equal(v.title, '고친 제목');
  assert.equal(v.head, '정보');
  assert.deepEqual(v.images.map((x) => x.id), [keep.id, added.id]);
  assert.ok(v.updatedAt >= v.at);
  assert.equal(env.drive.files.get(drop.id)._trashed, true);
  assert.equal(env.drive.files.get(keep.id)._trashed, false);
  assert.equal(rowOf(env, 'images', added.id)[1], id);
  assert.throws(() => env.run('apiUpdatePost', { device: B, id: id, pw: 'nope', title: 'x', content: 'y' }), /비밀번호가 맞지 않습니다/);
});

test('비밀번호로 글을 지우면 목록·보기에서 사라지고 사진도 정리된다', () => {
  const env = freshEnv();
  const img = upload(env, A);
  const { id } = post(env, A, { images: [img.id], pw: 'pass1' });
  assert.throws(() => env.run('apiDeletePost', { device: A, id: id, pw: 'pass2' }), /비밀번호가 맞지 않습니다/);
  assert.deepEqual(env.run('apiDeletePost', { device: B, id: id, pw: 'pass1' }), { ok: true, g: 'free' });
  assert.throws(() => env.run('apiView', { device: A, id: id }), /삭제되었거나/);
  assert.equal(env.run('apiList', { device: A, g: 'free' }).total, 0);
  assert.equal(env.drive.files.get(img.id)._trashed, true);
  assert.throws(() => env.run('apiImage', { id: img.id }), /삭제된 사진/);
  assert.throws(() => env.run('apiVote', { device: B, id: id, type: 'up' }), /삭제되었거나/);
});

test('비밀번호를 10번 틀리면 10분 동안 막힌다', () => {
  const env = freshEnv();
  const { id } = post(env, A, { pw: 'right' });
  for (let i = 0; i < 10; i++) assert.throws(() => env.run('apiDeletePost', { device: B, id: id, pw: 'x' + i }), /맞지 않습니다/);
  assert.throws(() => env.run('apiDeletePost', { device: B, id: id, pw: 'right' }), /너무 많이 틀렸습니다/);
  env.run('apiDeletePost', { device: C, id: id, pw: 'right' }); // 다른 기기는 괜찮음
});

/* ───────────── 관리자 ───────────── */

test('관리자 키 로그인 · 주인 자동 관리자 · 키 변경', () => {
  const env = freshEnv();
  assert.throws(() => env.run('apiAdminInfo', { device: A }), /관리자 권한이 필요/);
  assert.throws(() => env.run('apiAdminLogin', { device: A, key: 'wrong' }), /관리자 키가 맞지 않습니다/);
  const token = adminToken(env);
  assert.match(token, /^[0-9a-f]{32}$/);
  assert.equal(env.run('apiMe', { device: A, admin: token }).admin, true);
  assert.equal(env.run('apiMe', { device: A }).admin, false);
  // 대소문자·하이픈이 달라도 됨
  const loose = env.props._m.ADMIN_KEY.replace(/-/g, ' ').toUpperCase();
  assert.ok(env.run('apiAdminLogin', { device: B, key: loose }).token);

  env.activeUser = env.ownerEmail;
  assert.deepEqual(env.run('apiMe', { device: A }), { admin: true, owner: true, code: env.run('apiMe', { device: A }).code });
  env.activeUser = 'someone@example.com';
  assert.equal(env.run('apiMe', { device: A }).admin, false);
  env.activeUser = '';

  env.runAsEditor('resetAdminKey');
  assert.equal(env.run('apiMe', { device: A, admin: token }).admin, false, '키를 바꾸면 기존 로그인은 무효');
  env.run('apiAdminLogout', { device: A, admin: token });

  for (let i = 0; i < 5; i++) assert.throws(() => env.run('apiAdminLogin', { device: C, key: 'bad' + i }), /맞지 않습니다/);
  assert.throws(() => env.run('apiAdminLogin', { device: C, key: env.props._m.ADMIN_KEY }), /너무 많습니다/);
});

test('관리자는 비밀번호 없이 지우고, 공지를 올리고, 작성자를 차단한다', () => {
  const env = freshEnv();
  const token = adminToken(env);
  const { id } = post(env, B, { title: '도배글' });
  const c = env.run('apiAddComment', { device: B, postId: id, pw: '1234', content: '도배 댓글' }).comments[0];

  assert.equal(env.run('apiAdminNotice', { device: A, admin: token, id: id, on: true }).notice, true);
  const list = env.run('apiList', { device: C, g: 'free' });
  assert.equal(list.notices.length, 2);
  assert.equal(list.total, 0, '공지는 위쪽에만');
  assert.equal(env.run('apiList', { device: C, g: 'free', mode: 'notice' }).total, 2);

  const ban = env.run('apiAdminBan', { device: A, admin: token, kind: 'comment', id: c.id, days: 7, reason: '도배' });
  assert.match(ban.code, /^[0-9a-f]{4}$/);
  assert.throws(() => post(env, B), /차단된 사용자입니다 \(.+까지\) · 사유: 도배/);
  assert.throws(() => upload(env, B), /차단된 사용자/);
  assert.throws(() => env.run('apiAddComment', { device: B, postId: id, pw: '1234', content: 'x' }), /차단된 사용자/);
  assert.throws(() => env.run('apiVote', { device: B, id: 1, type: 'up' }), /차단된 사용자/);
  post(env, C); // 다른 사람은 괜찮음

  env.run('apiDeleteComment', { device: A, admin: token, id: c.id });
  env.run('apiDeletePost', { device: A, admin: token, id: id });
  assert.throws(() => env.run('apiAdminBan', { device: A, admin: token, kind: 'post', id: 1 }), /관리자는 차단할 수 없습니다/);

  const info = env.run('apiAdminInfo', { device: A, admin: token });
  assert.equal(info.bans.length, 1);
  assert.equal(info.bans[0].active, true);
  assert.match(info.sheetUrl, /^https:\/\/docs\.google\.com\/spreadsheets\/d\//);
  const after = env.run('apiAdminUnban', { device: A, admin: token, target: info.bans[0].target });
  assert.deepEqual(after.bans, []);
  post(env, B);

  // 영구 차단
  const p2 = post(env, C);
  const perm = env.run('apiAdminBan', { device: A, admin: token, kind: 'post', id: p2.id, days: 0 });
  assert.equal(perm.until, 0);
  assert.throws(() => post(env, C), /\(영구\)/);
});

test('관리자 글·댓글은 남이 지우거나 고칠 수 없다', () => {
  const env = freshEnv();
  const token = adminToken(env);
  const { id } = env.run('apiCreatePost', { device: A, admin: token, g: 'free', title: '운영 공지', content: '내용', nick: '' });
  const v = env.run('apiView', { device: B, id: id }).post;
  assert.equal(v.admin, true);
  assert.equal(v.nick, '관리자');
  assert.equal(v.code, '');
  assert.throws(() => env.run('apiDeletePost', { device: B, id: id, pw: '' }), /관리자 글은 삭제할 수 없습니다/);
  assert.throws(() => env.run('apiPostForEdit', { device: B, id: id, pw: '' }), /관리자만 수정/);
  assert.equal(env.run('apiPostForEdit', { device: A, admin: token, id: id }).post.title, '운영 공지');
  const c = env.run('apiAddComment', { device: A, admin: token, postId: id, content: '관리자 댓글' }).comments[0];
  assert.equal(c.admin, true);
  assert.throws(() => env.run('apiDeleteComment', { device: B, id: c.id, pw: '' }), /관리자 댓글은 삭제할 수 없습니다/);
});

test('갤러리 만들기·숨기기', () => {
  const env = freshEnv();
  const token = adminToken(env);
  const save = (gallery, create) => env.run('apiAdminSaveGallery', { device: A, admin: token, create: create, gallery: gallery });
  assert.throws(() => save({ id: '1abc', name: 'x' }, true), /갤러리 ID/);
  assert.throws(() => save({ id: 'free', name: '중복' }, true), /이미 있는 갤러리 ID/);
  assert.throws(() => save({ id: 'game', name: '' }, true), /이름을/);
  const res = save({ id: 'Game', name: '게임', desc: '게임 이야기', heads: '일반, 공략 ,일반,', threshold: '3' }, true);
  const g = res.galleries.find((x) => x.id === 'game');
  assert.deepEqual(g.heads, ['일반', '공략']);
  assert.equal(g.threshold, 3);
  assert.equal(g.order, 5);
  assert.equal(post(env, A, { g: 'game', head: '공략' }).no, 1);

  save({ id: 'game', name: '게임', desc: '', heads: '일반', threshold: 3, order: 9, hidden: true }, false);
  assert.ok(!env.run('apiHome', { device: B }).galleries.some((x) => x.id === 'game'));
  assert.throws(() => env.run('apiList', { device: B, g: 'game' }), /존재하지 않는 갤러리/);
  assert.throws(() => post(env, B, { g: 'game' }), /존재하지 않는 갤러리/);
  assert.equal(env.run('apiList', { device: A, admin: token, g: 'game' }).total, 1);
  const boot = JSON.parse(/window\.__BOOT__ = (.*?);<\/script>/.exec(env.doGet({}).getContent())[1]);
  assert.ok(!boot.galleries.some((x) => x.id === 'game'));
});

/* ───────────── 검색 · 페이지 ───────────── */

test('검색(제목·내용·글쓴이·말머리)과 페이지 나누기', () => {
  const env = freshEnv();
  for (let i = 1; i <= 35; i++) {
    post(env, A, { title: '글 ' + i, content: i === 7 ? '그가 "안녕" 이라고 했다' : '평범한 내용 ' + i, nick: i % 2 ? '홀수' : '짝수', head: i <= 3 ? '질문' : '일반' });
  }
  const p1 = env.run('apiList', { device: A, g: 'free' });
  assert.equal(p1.total, 35);
  assert.equal(p1.pages, 2);
  assert.equal(p1.posts.length, 30);
  assert.equal(p1.posts[0].title, '글 35');
  const p2 = env.run('apiList', { device: A, g: 'free', p: 2 });
  assert.equal(p2.posts.length, 5);
  assert.equal(env.run('apiList', { device: A, g: 'free', p: 99 }).page, 2);
  assert.equal(env.run('apiList', { device: A, g: 'free', p: -3 }).page, 1);

  assert.equal(env.run('apiList', { device: A, g: 'free', q: '글 1', st: 'title' }).total, 11); // 1, 10~19
  assert.equal(env.run('apiList', { device: A, g: 'free', q: '"안녕"', st: 'content' }).total, 1);
  assert.equal(env.run('apiList', { device: A, g: 'free', q: '평범한', st: 'all' }).total, 34);
  assert.equal(env.run('apiList', { device: A, g: 'free', q: '짝수', st: 'nick' }).total, 17);
  assert.equal(env.run('apiList', { device: A, g: 'free', head: '질문' }).total, 3);
  assert.equal(env.run('apiList', { device: A, g: 'free', head: '없는말머리' }).head, '');
  const searched = env.run('apiList', { device: A, g: 'free', q: '이용 안내', st: 'title' });
  assert.equal(searched.total, 1, '검색할 때는 공지도 결과에 포함');
  assert.equal(searched.notices.length, 0);
});

/* ───────────── 리뷰에서 나온 공격·한도 ───────────── */

test('식별값을 바꿔 가며 비밀번호를 맞혀 봐도 글마다 20번 틀리면 잠긴다', () => {
  const env = freshEnv();
  const { id } = post(env, A, { pw: '0042' });
  for (let i = 0; i < 20; i++) {
    const dev = String(i).padStart(2, '0').repeat(16);
    assert.throws(() => env.run('apiPostForEdit', { device: dev, id: id, pw: String(1000 + i) }), /맞지 않습니다/);
  }
  assert.throws(() => env.run('apiPostForEdit', { device: 'e'.repeat(32), id: id, pw: '0042' }), /틀린 횟수가 많아/);
  assert.throws(() => env.run('apiDeletePost', { device: 'f'.repeat(32), id: id, pw: '0042' }), /틀린 횟수가 많아/);
  // 관리자는 여전히 지울 수 있고, 다른 글은 영향 없음
  const other = post(env, B, { pw: '9999' });
  assert.equal(env.run('apiPostForEdit', { device: C, id: other.id, pw: '9999' }).post.id, other.id);
  env.run('apiDeletePost', { device: A, admin: adminToken(env), id: id });
  // 댓글도 따로 센다
  const c = env.run('apiAddComment', { device: B, postId: other.id, pw: '1111', content: 'x' }).comments[0];
  for (let i = 0; i < 20; i++) {
    assert.throws(() => env.run('apiDeleteComment', { device: String(i % 10).repeat(32), id: c.id, pw: 'no' + i }), /맞지 않습니다|너무 많이 틀렸습니다/);
  }
  assert.throws(() => env.run('apiDeleteComment', { device: 'd'.repeat(32), id: c.id, pw: '1111' }), /틀린 횟수가 많아/);
});

test('사이트 전체 글·댓글 한도 (식별값을 바꿔도 적용, 관리자는 제외)', () => {
  const env = freshEnv({ patch: NO_COOLDOWN + ' CONFIG.SITE_POSTS_PER_10MIN = 2; CONFIG.SITE_COMMENTS_PER_10MIN = 1;' });
  post(env, A);
  post(env, B);
  assert.throws(() => post(env, C), /글이 너무 많이 올라와서/);
  const token = adminToken(env);
  const res = env.run('apiCreatePost', { device: C, admin: token, g: 'free', title: '관리자', content: 'x' });
  env.run('apiAddComment', { device: A, postId: res.id, pw: '1234', content: '1' });
  assert.throws(() => env.run('apiAddComment', { device: B, postId: res.id, pw: '1234', content: '2' }), /댓글이 너무 많이 올라와서/);
  // 실패한 시도(검사에서 걸린 요청)는 한도를 쓰지 않는다
  const env2 = freshEnv({ patch: NO_COOLDOWN + ' CONFIG.SITE_POSTS_PER_10MIN = 1;' });
  assert.throws(() => post(env2, A, { title: '' }), /제목을 입력/);
  post(env2, A);
});

test('사이트 전체 하루 사진 업로드 한도', () => {
  const env = freshEnv({ patch: NO_COOLDOWN + ' CONFIG.SITE_UPLOADS_PER_DAY = 2;' });
  upload(env, A);
  upload(env, B);
  assert.throws(() => upload(env, C), /업로드 한도/);
  const files = env.drive.files.size;
  // 하루가 지난 기록은 세지 않는다
  env.sheet('images')._rows.forEach((r, i) => { if (i > 0) r[8] = Date.now() - 2 * 24 * 3600 * 1000; });
  upload(env, C);
  assert.equal(env.drive.files.size, files + 1);
  const env2 = freshEnv({ patch: NO_COOLDOWN + ' CONFIG.SITE_UPLOAD_MB_PER_DAY = 0.0001;' });
  upload(env2, A); // 68바이트
  assert.throws(() => upload(env2, B), /업로드 한도/);
});

test('사진 목록 등록에 실패하면 올린 파일을 바로 지운다', () => {
  const env = freshEnv();
  env.lockBusy = true;
  const before = Array.from(env.drive.files.values()).filter((f) => !f._trashed).length;
  assert.throws(() => upload(env, A), /처리가 늦어지고/);
  env.lockBusy = false;
  const leftovers = Array.from(env.drive.files.values()).filter((f) => !f._trashed).length;
  assert.equal(leftovers, before);
});

test('마지막 행을 지워도 번호를 다시 쓰지 않는다 (옛 댓글·추천이 새 글에 붙지 않게)', () => {
  const env = freshEnv();
  const { id } = post(env, A, { title: '지워질 글' });
  env.run('apiAddComment', { device: B, postId: id, pw: '1234', content: '옛 댓글' });
  env.run('apiVote', { device: B, id: id, type: 'up' });
  const sh = env.sheet('posts');
  sh.deleteRow(sh.getLastRow());
  const next = post(env, C, { title: '새 글' });
  assert.equal(next.id, id + 1);
  const v = env.run('apiView', { device: B, id: next.id });
  assert.equal(v.comments.length, 0);
  assert.deepEqual(env.run('apiVote', { device: B, id: next.id, type: 'up' }), { up: 1, down: 0 });
});

test('내용 검색은 저장 형식(JSON의 \\n)에 잘못 걸리지 않는다', () => {
  const env = freshEnv();
  post(env, A, { title: '줄바꿈', content: '첫 줄\n둘째 줄' });
  post(env, A, { title: '영어', content: 'internet' });
  assert.equal(env.run('apiList', { device: A, g: 'free', q: 'n', st: 'content' }).total, 1);
  assert.equal(env.run('apiList', { device: A, g: 'free', q: '줄\n둘', st: 'content' }).total, 0, '검색어 줄바꿈은 공백으로 바뀜');
  assert.equal(env.run('apiList', { device: A, g: 'free', q: '줄 둘', st: 'content' }).total, 0);
  assert.equal(env.run('apiList', { device: A, g: 'free', q: '둘째', st: 'content' }).total, 1);
});

test('시트를 지운 뒤 속성을 지우고 setup 을 다시 하면 새로 만들어진다', () => {
  const env = freshEnv();
  const oldId = env.props._m.SPREADSHEET_ID;
  env.run('apiList', { device: A, g: 'free' }); // 속성이 캐시에 올라간 상태
  env.sheets.delete(oldId);
  assert.throws(() => env.run('apiList', { device: A, g: 'free' }), /스프레드시트를 열 수 없습니다/);
  delete env.props._m.SPREADSHEET_ID;
  env.runAsEditor('setup');
  assert.notEqual(env.props._m.SPREADSHEET_ID, oldId);
  assert.equal(env.run('apiList', { device: A, g: 'free' }).notices.length, 1);
});

test('차단 목록이 커져서 캐시에 못 넣어도 글쓰기는 계속 된다', () => {
  const env = freshEnv();
  const sh = env.sheet('bans');
  for (let i = 0; i < 400; i++) {
    sh.appendRow([JSON.stringify(String(i).padStart(40, '0')), JSON.stringify('abcd'), 0, JSON.stringify('도배와 욕설이 심해서 차단합니다 '.repeat(5)), Date.now()]);
  }
  post(env, A);
  assert.equal(env.run('apiAdminInfo', { device: A, admin: adminToken(env) }).bans.length, 400);
});

test('조회가 아무리 많아도 캐시 항목 한도 때문에 도배 방지가 풀리지 않는다', () => {
  const env = freshEnv({ patch: 'CONFIG.COMMENT_COOLDOWN_SEC = 0;' });
  const { id } = post(env, A);
  for (let i = 0; i < 1200; i++) env.run('apiView', { device: i.toString(16).padStart(32, '0'), id: id });
  assert.equal(env.stats.cacheEvictions, 0);
  assert.throws(() => post(env, A), /20초에 한 번만/);
});

test('원댓글이 지워진 스레드의 답글에도 답글을 달 수 있다', () => {
  const env = freshEnv();
  const { id } = post(env, A);
  const top = env.run('apiAddComment', { device: B, postId: id, pw: '1111', content: '원댓글' }).comments[0];
  const reply = env.run('apiAddComment', { device: C, postId: id, parentId: top.id, pw: '2222', content: '답글' }).comments[1];
  env.run('apiDeleteComment', { device: B, id: top.id, pw: '1111' });
  const res = env.run('apiAddComment', { device: A, postId: id, parentId: reply.id, pw: '3333', content: '답글의 답글' });
  assert.deepEqual(res.comments.map((c) => [c.parent, c.deleted]), [[0, true], [top.id, false], [top.id, false]]);
});

/* ───────────── 견고함 ───────────── */

test('누가 시트에서 행을 지우거나 시트를 지워도 계속 동작한다', () => {
  const env = freshEnv();
  post(env, A, { title: '2번' });
  post(env, A, { title: '3번' });
  post(env, A, { title: '4번' });
  env.sheet('posts').deleteRow(3); // id 2 행 삭제 → id 3, 4 가 한 칸씩 위로
  assert.equal(env.run('apiView', { device: A, id: 4 }).post.title, '4번');
  assert.throws(() => env.run('apiView', { device: A, id: 2 }), /삭제되었거나/);
  assert.equal(post(env, A, { title: '5번' }).id, 5, 'id 는 겹치지 않는다');
  assert.equal(env.run('apiView', { device: A, id: 5 }).post.title, '5번');

  const ss = env.sheets.get(env.props._m.SPREADSHEET_ID);
  ss.deleteSheet(ss.getSheetByName('votes'));
  assert.deepEqual(env.run('apiVote', { device: B, id: 5, type: 'up' }), { up: 1, down: 0 });
  assert.ok(ss.getSheetByName('votes'));
});

test('잠금을 못 얻으면 친절한 오류를 낸다', () => {
  const env = freshEnv();
  env.lockBusy = true;
  assert.throws(() => post(env, A), /처리가 늦어지고 있습니다/);
  env.lockBusy = false;
  post(env, A);
});

test('반환값에 Date 가 섞이지 않는다 (google.script.run 제약)', () => {
  const env = freshEnv();
  // 관리자가 시트에서 날짜 칸을 직접 고쳐 Date 가 들어가도 숫자로 바꿔서 돌려준다
  post(env, A);
  env.sheet('posts')._rows[2][8] = new Date('2026-01-02T03:04:05Z');
  const res = env.run('apiList', { device: A, g: 'free' });
  assert.equal(res.posts[0].at, Date.parse('2026-01-02T03:04:05Z'));
  env.run('apiHome', { device: A });
  env.run('apiView', { device: A, id: 2, list: { p: 1 } });
});
