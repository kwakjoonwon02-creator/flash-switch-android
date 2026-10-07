/**
 * ─────────────────────────────────────────────────────────────────────────
 *  모두의 갤러리 — Google Apps Script만으로 돌아가는 익명 갤러리 커뮤니티
 *  (디시인사이드 스타일: 갤러리 · 말머리 · 개념글 · 추천/비추 · 댓글/답글 · 사진 첨부)
 * ─────────────────────────────────────────────────────────────────────────
 *  · 글/댓글/추천/차단/갤러리 정보 → 자동으로 만들어지는 Google 스프레드시트
 *  · 사진 → Google Drive 폴더
 *  · 처음 한 번 편집기에서 setup() 을 실행한 뒤 웹 앱으로 배포하세요. (README.md 참고)
 *
 *  이름이 _ 로 끝나는 함수는 내부용이라 브라우저(google.script.run)에서 호출할 수 없습니다.
 *  이름이 api 로 시작하는 함수가 브라우저에서 호출하는 API 입니다.
 */

/** 운영 설정 — 필요하면 값을 바꾸고 새 버전으로 다시 배포하세요. */
const CONFIG = {
  SITE_NAME: '모두의 갤러리',
  SITE_DESC: '누구나 익명으로 글과 사진을 올리는 갤러리 커뮤니티',
  TIME_ZONE: 'Asia/Seoul',
  PAGE_SIZE: 30,                 // 목록 한 페이지 글 수
  DEFAULT_NICK: 'ㅇㅇ',          // 닉네임을 비우면 쓰는 이름
  MAX_NICK: 20,
  MIN_PASSWORD: 4,
  MAX_PASSWORD: 40,
  MAX_TITLE: 80,
  MAX_CONTENT: 20000,
  MAX_COMMENT: 500,
  MAX_IMAGES: 20,                // 글 하나에 올릴 수 있는 사진 수
  MAX_IMAGE_MB: 5,               // 사진 한 장 최대 용량 (브라우저에서 압축한 뒤 기준, GIF는 원본)
  POST_COOLDOWN_SEC: 20,         // 도배 방지: 같은 기기의 글 작성 간격
  COMMENT_COOLDOWN_SEC: 5,       // 도배 방지: 같은 기기의 댓글 작성 간격
  UPLOADS_PER_10MIN: 60,         // 같은 기기가 10분 동안 올릴 수 있는 사진 수
  // 사이트 전체 한도: 식별값을 바꿔 가며 도배해도 이 이상은 못 올림 (관리자는 제외)
  SITE_POSTS_PER_10MIN: 60,
  SITE_COMMENTS_PER_10MIN: 300,
  SITE_UPLOAD_MB_PER_DAY: 1024,  // 하루 동안 사이트 전체에 올라오는 사진 용량 (구글 드라이브 15GB 보호)
  SITE_UPLOADS_PER_DAY: 3000,
  BEST_HOURS: 72,                // 실시간 베스트 집계 기간(시간)
  BEST_COUNT: 10,
  RESERVED_NICKS: ['운영자', '관리자', 'admin'], // 관리자가 아니면 닉네임에 쓸 수 없는 단어
  // 공유 주소가 .../dev 로 나오면 배포한 웹 앱 주소(.../exec)를 여기에 넣으세요. 비워 두면 자동.
  WEB_APP_URL: '',
  // 처음 setup 때 만들어지는 갤러리 (이후에는 관리자 페이지나 galleries 시트에서 수정)
  GALLERIES: [
    { id: 'free', name: '자유', desc: '아무 이야기나 자유롭게', heads: ['일반', '정보', '질문', '사진'], threshold: 5 },
    { id: 'photo', name: '사진', desc: '직접 찍은 사진과 짤을 올리는 곳', heads: ['일반', '풍경', '일상', '동물', '음식'], threshold: 5 },
    { id: 'humor', name: '유머', desc: '웃긴 글, 웃긴 짤', heads: ['일반', '유머', '짤'], threshold: 5 },
    { id: 'suggest', name: '건의', desc: '커뮤니티 운영 건의와 버그 제보', heads: ['건의', '버그', '답변완료'], threshold: 3 },
  ],
};

/** 스프레드시트 시트별 열 구성. 순서를 바꾸면 기존 데이터와 맞지 않으니 끝에만 추가하세요. */
const SCHEMA = {
  galleries: ['id', 'name', 'desc', 'heads', 'threshold', 'order', 'hidden', 'lastNo', 'createdAt'],
  posts: ['id', 'gallery', 'no', 'head', 'title', 'nick', 'code', 'admin', 'createdAt', 'updatedAt',
    'views', 'up', 'down', 'comments', 'imageCount', 'thumb', 'notice', 'deleted',
    'device', 'pwHash', 'salt', 'images', 'content'],
  comments: ['id', 'postId', 'parentId', 'nick', 'code', 'admin', 'createdAt', 'deleted',
    'device', 'pwHash', 'salt', 'content'],
  images: ['id', 'postId', 'device', 'mime', 'width', 'height', 'bytes', 'public', 'createdAt', 'deleted'],
  votes: ['key', 'postId', 'type', 'createdAt'],
  bans: ['device', 'code', 'until', 'reason', 'createdAt'],
};

const COL = Object.keys(SCHEMA).reduce(function (all, name) {
  all[name] = SCHEMA[name].reduce(function (m, key, i) { m[key] = i; return m; }, {});
  return all;
}, {});
const G = COL.galleries;
const P = COL.posts;
const C = COL.comments;
const I = COL.images;
const B = COL.bans;
// 목록에는 본문/비밀번호 같은 무거운 열이 필요 없어서 deleted 열까지만 읽습니다.
const POST_META_WIDTH = P.deleted + 1;
const DAY_MS = 24 * 60 * 60 * 1000;

/* ───────────────────────────── 웹 앱 진입점 ───────────────────────────── */

function doGet(e) {
  const params = cleanParams_(e && e.parameter);
  let title = CONFIG.SITE_NAME;
  let boot;
  try {
    ensureReady_();
    const owner = isOwner_();
    const galleries = allGalleries_()
      .filter(function (g) { return owner || !g.hidden; })
      .map(function (g) { return publicGallery_(g, owner); });
    const byId = {};
    galleries.forEach(function (g) { byId[g.id] = g; });
    if (params.id) {
      const found = findById_('posts', int_(params.id));
      const g = found && byId[str_(found.r[P.gallery])];
      if (found && g && !bool_(found.r[P.deleted])) title = str_(found.r[P.title]) + ' - ' + g.name + ' 갤러리';
    } else if (params.g && byId[params.g]) {
      title = byId[params.g].name + ' 갤러리 - ' + CONFIG.SITE_NAME;
    }
    boot = {
      ok: true,
      site: { name: CONFIG.SITE_NAME, desc: CONFIG.SITE_DESC },
      url: webAppUrl_(),
      params: params,
      galleries: galleries,
      owner: owner,
      limits: {
        pageSize: CONFIG.PAGE_SIZE,
        defaultNick: CONFIG.DEFAULT_NICK,
        maxNick: CONFIG.MAX_NICK,
        minPassword: CONFIG.MIN_PASSWORD,
        maxPassword: CONFIG.MAX_PASSWORD,
        maxTitle: CONFIG.MAX_TITLE,
        maxContent: CONFIG.MAX_CONTENT,
        maxComment: CONFIG.MAX_COMMENT,
        maxImages: CONFIG.MAX_IMAGES,
        maxImageMB: CONFIG.MAX_IMAGE_MB,
        bestHours: CONFIG.BEST_HOURS,
        timeZone: CONFIG.TIME_ZONE,
      },
    };
  } catch (err) {
    console.error(err && err.stack ? err.stack : err);
    boot = { ok: false, error: String(err && err.message ? err.message : err), site: { name: CONFIG.SITE_NAME } };
  }
  const t = HtmlService.createTemplateFromFile('Index');
  t.styles = HtmlService.createHtmlOutputFromFile('Styles').getContent();
  t.script = HtmlService.createHtmlOutputFromFile('App').getContent();
  t.boot = safeJson_(boot);
  return t.evaluate()
    .setTitle(title.slice(0, 100))
    .addMetaTag('viewport', 'width=device-width, initial-scale=1');
}

/* ───────────────────────────── 편집기에서 실행하는 함수 ───────────────────────────── */

/**
 * 처음 한 번 실행하세요. 데이터 스프레드시트·사진 폴더·관리자 키를 만들고
 * 결과(관리자 키 포함)를 실행 로그에 보여 줍니다. 여러 번 실행해도 안전합니다.
 */
function setup() {
  // 시트·폴더를 지우고 스크립트 속성을 고친 뒤 다시 실행하는 경우를 위해 캐시된 속성을 버립니다.
  resetPropsCache_();
  // 이미 설정된 뒤에는 주인만 실행할 수 있습니다. (공개 함수라 방문자도 google.script.run 으로 부를 수 있음)
  if (isReady_(PropertiesService.getScriptProperties().getProperties()) && !isOwner_()) {
    throw new Error('스크립트 편집기에서 직접 실행해 주세요.');
  }
  const p = ensureReady_();
  const ss = openSpreadsheet_(p.SPREADSHEET_ID);
  withLock_(function () { ensureSchema_(ss); }, 30000);
  installTriggers_();
  const url = webAppUrl_();
  [
    '✅ 설정이 끝났습니다.',
    '· 데이터 스프레드시트: ' + ss.getUrl(),
    '· 사진 폴더: https://drive.google.com/drive/folders/' + p.IMAGE_FOLDER_ID,
    '· 관리자 키: ' + p.ADMIN_KEY,
    '  (웹 앱 아래쪽 [관리자] 버튼에 입력하세요. 이 스크립트 주인의 구글 계정으로 접속하면 자동으로 관리자입니다.)',
    url ? '· 웹 앱 주소: ' + url : '· 이제 [배포] → [새 배포] → 유형 "웹 앱", 실행: 나, 액세스: 모든 사용자 로 배포하세요.',
  ].forEach(function (line) { console.log(line); });
  // 보안: 반환값은 브라우저에서도 받을 수 있으므로 아무것도 돌려주지 않습니다.
}

/** 관리자 키를 새로 만듭니다. 이전 키와 로그인 세션은 모두 무효가 됩니다. */
function resetAdminKey() {
  if (!isOwner_()) throw new Error('스크립트 편집기에서 직접 실행해 주세요.');
  const key = newAdminKey_();
  PropertiesService.getScriptProperties().setProperty('ADMIN_KEY', key);
  resetPropsCache_();
  console.log('🔑 새 관리자 키: ' + key);
}

/** 글에 붙지 않은 채 하루가 지난 사진(쓰다 만 글)을 정리합니다. setup() 이 6시간마다 실행되도록 등록합니다. */
function cleanupOrphanImages() {
  const cache = CacheService.getScriptCache();
  if (cache.get('cleanup:running')) return;
  cachePut_('cleanup:running', '1', 600);
  const cutoff = Date.now() - DAY_MS;
  // 잠금 안에서는 삭제 표시만 하고(빠름), 드라이브 휴지통 이동은 잠금 밖에서 합니다.
  const ids = withLock_(function () {
    const sh = sheet_('images');
    const last = sh.getLastRow();
    if (last < 2) return [];
    const rows = sh.getRange(2, 1, last - 1, SCHEMA.images.length).getValues();
    const found = [];
    rows.forEach(function (r) {
      if (found.length < 1000 && !num_(r[I.postId]) && !bool_(r[I.deleted]) && num_(r[I.createdAt]) < cutoff) found.push(str_(r[I.id]));
    });
    return markImagesDeleted_(found);
  }, 30000);
  trashFiles_(ids);
  if (ids.length) console.log('정리한 사진: ' + ids.length + '장');
}

/* ───────────────────────────── 공개 API (google.script.run) ───────────────────────────── */

function apiMe(req) {
  return run_(function () {
    req = obj_(req);
    const dev = deviceOrNull_(req);
    return { admin: isAdmin_(req), owner: isOwner_(), code: dev ? dev.code : '' };
  });
}

function apiHome(req) {
  return run_(function () {
    req = obj_(req);
    const admin = isAdmin_(req);
    const galleries = allGalleries_().filter(function (g) { return admin || !g.hidden; });
    const byId = {};
    galleries.forEach(function (g) { byId[g.id] = { g: g, total: 0, today: 0 }; });
    const metas = loadMetas_().filter(function (m) { return byId[m.g]; });
    const today = startOfToday_();
    metas.forEach(function (m) {
      byId[m.g].total++;
      if (m.at >= today) byId[m.g].today++;
    });
    const since = Date.now() - CONFIG.BEST_HOURS * 3600 * 1000;
    const best = metas
      .filter(function (m) { return !m.notice && m.at >= since && m.up > 0 && m.up > m.down; })
      .sort(function (a, b) { return (b.up - b.down) - (a.up - a.down) || b.views - a.views || b.id - a.id; })
      .slice(0, CONFIG.BEST_COUNT);
    const concept = metas
      .filter(function (m) { return m.up >= byId[m.g].g.threshold; })
      .sort(byIdDesc_)
      .slice(0, 10);
    const recent = metas.filter(function (m) { return !m.notice; }).sort(byIdDesc_).slice(0, 15);
    return {
      total: metas.length,
      galleries: galleries.map(function (g) {
        const x = publicGallery_(g, admin);
        x.total = byId[g.id].total;
        x.today = byId[g.id].today;
        return x;
      }),
      best: best.map(publicMeta_),
      concept: concept.map(publicMeta_),
      recent: recent.map(publicMeta_),
    };
  });
}

function apiList(req) {
  return run_(function () {
    req = obj_(req);
    const g = gallery_(req.g, isAdmin_(req));
    return listOf_(g, req, loadMetas_());
  });
}

function apiView(req) {
  return run_(function () {
    req = obj_(req);
    const admin = isAdmin_(req);
    const found = findById_('posts', int_(req.id));
    if (!found || bool_(found.r[P.deleted])) fail_('삭제되었거나 존재하지 않는 글입니다.');
    const m = toMeta_(found.r, found.row);
    const g = gallery_(m.g, admin);
    // 조회수: 같은 브라우저의 6시간 안 재방문은 브라우저가 seen 으로 알려 줍니다.
    // (서버 캐시에 글×기기마다 기록하면 CacheService 항목 한도(약 1000개)를 금방 채워
    //  도배 방지 같은 다른 기록이 밀려나기 때문에 서버에는 남기지 않습니다.)
    if (!req.seen && deviceOrNull_(req)) {
      m.views += 1;
      sheet_('posts').getRange(found.row, P.views + 1).setValue(m.views);
    }
    const post = publicMeta_(m);
    post.updatedAt = num_(found.r[P.updatedAt]);
    post.images = images_(found.r[P.images]);
    post.content = str_(found.r[P.content]);
    const out = { gallery: publicGallery_(g, admin), post: post, comments: commentsOf_(m.id) };
    if (req.list) out.list = listOf_(g, req.list, loadMetas_());
    return out;
  });
}

/**
 * 사진 한 장을 Drive에 비공개로 저장합니다. (글 작성 전에 사진마다 따로 호출)
 * 글에 붙을 때 공개되므로, 글에 안 쓴 파일이 공개 이미지 호스팅처럼 쓰이지 않습니다.
 */
function apiUploadImage(req) {
  return run_(function () {
    req = obj_(req);
    const dev = device_(req);
    const admin = isAdmin_(req);
    if (!admin) {
      checkBan_(dev);
      hit_('up:' + dev.hash, CONFIG.UPLOADS_PER_10MIN, 600, '사진을 너무 많이 올렸습니다. 잠시 뒤에 다시 시도해 주세요.');
    }
    const maxBytes = CONFIG.MAX_IMAGE_MB * 1024 * 1024;
    const tooBig = '사진은 한 장에 ' + CONFIG.MAX_IMAGE_MB + 'MB까지 올릴 수 있습니다.';
    const data = typeof req.data === 'string' ? req.data.replace(/^data:[^,]*,/, '') : '';
    if (!data) fail_('사진 데이터가 없습니다.');
    if (data.length > Math.ceil(maxBytes / 3) * 4 + 16) fail_(tooBig);
    let bytes;
    try {
      bytes = Utilities.base64Decode(data);
    } catch (e) {
      fail_('사진 데이터가 올바르지 않습니다.');
    }
    if (!bytes || !bytes.length) fail_('사진 데이터가 없습니다.');
    if (bytes.length > maxBytes) fail_(tooBig);
    const mime = sniffImage_(bytes);
    if (!mime) fail_('jpg, png, gif, webp 사진만 올릴 수 있습니다.');
    if (!admin) checkUploadBudget_(bytes.length);
    const ext = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/gif': 'gif', 'image/webp': 'webp' }[mime];
    const file = imageFolder_().createFile(Utilities.newBlob(bytes, mime, 'img_' + Date.now() + '_' + dev.code + '.' + ext));
    const id = file.getId();
    const w = clampInt_(req.w, 0, 30000);
    const h = clampInt_(req.h, 0, 30000);
    try {
      withLock_(function () {
        sheet_('images').appendRow([enc_(id), 0, enc_(dev.hash), enc_(mime), w, h, bytes.length, false, Date.now(), false]);
      });
    } catch (e) {
      trashFiles_([id]); // 목록에 못 올린 파일은 정리 대상에서도 빠지므로 바로 지웁니다.
      throw e;
    }
    return { id: id, mime: mime, w: w, h: h, pub: false };
  });
}

/** 공개 링크로 못 보여 주는 사진(GIF 움짤 재생, 공유가 막힌 계정)을 base64로 돌려줍니다. */
function apiImage(req) {
  return run_(function () {
    req = obj_(req);
    const id = String(req.id || '');
    if (!isFileId_(id)) fail_('사진 정보가 올바르지 않습니다.');
    // 이 앱으로 올려 글에 붙은 사진만 내보냅니다. (드라이브의 다른 파일은 절대 읽지 않음)
    const sh = sheet_('images');
    const last = sh.getLastRow();
    const cell = last < 2 ? null : sh.getRange(2, I.id + 1, last - 1, 1)
      .createTextFinder(enc_(id)).matchEntireCell(true).matchCase(true).findNext();
    if (!cell) fail_('사진을 찾을 수 없습니다.');
    const r = sh.getRange(cell.getRow(), 1, 1, SCHEMA.images.length).getValues()[0];
    if (str_(r[I.id]) !== id || bool_(r[I.deleted])) fail_('삭제된 사진입니다.');
    if (!num_(r[I.postId])) fail_('글에 첨부되지 않은 사진입니다.');
    const blob = DriveApp.getFileById(id).getBlob();
    return { mime: str_(r[I.mime]) || blob.getContentType(), data: Utilities.base64Encode(blob.getBytes()) };
  });
}

function apiCreatePost(req) {
  return run_(function () {
    req = obj_(req);
    const dev = device_(req);
    const admin = isAdmin_(req);
    if (req.hp) fail_('잘못된 요청입니다.');
    if (!admin) checkBan_(dev);
    const g = gallery_(req.g, admin);
    const f = postFields_(req, g);
    const nick = cleanNick_(req.nick, admin);
    const pw = admin && !req.pw ? '' : cleanPassword_(req.pw);
    const ids = imageIds_(req.images);
    if (!f.content && !ids.length) fail_('내용을 입력해 주세요.');
    const cache = CacheService.getScriptCache();
    const cdKey = 'cd:post:' + dev.hash;
    if (!admin) {
      if (cache.get(cdKey)) fail_('도배 방지를 위해 ' + CONFIG.POST_COOLDOWN_SEC + '초에 한 번만 글을 쓸 수 있습니다.');
      checkSiteLimit_('post', CONFIG.SITE_POSTS_PER_10MIN, '지금 글이 너무 많이 올라와서 잠시 글쓰기를 막았습니다. 몇 분 뒤에 다시 시도해 주세요.');
    }
    const shared = shareImages_(ids, dev, admin, 0);
    const result = withLock_(function () {
      const sh = sheet_('posts');
      const imgs = claimImages_(ids, dev, admin, 0, shared);
      const id = nextId_(sh, 'LAST_POST_ID');
      const no = bumpGalleryNo_(g.id);
      const salt = pw ? randomHex_(16) : '';
      const now = Date.now();
      const row = new Array(SCHEMA.posts.length).fill('');
      row[P.id] = id;
      row[P.gallery] = enc_(g.id);
      row[P.no] = no;
      row[P.head] = enc_(f.head);
      row[P.title] = enc_(f.title);
      row[P.nick] = enc_(nick);
      row[P.code] = enc_(dev.code);
      row[P.admin] = admin;
      row[P.createdAt] = now;
      row[P.updatedAt] = now;
      row[P.views] = 0;
      row[P.up] = 0;
      row[P.down] = 0;
      row[P.comments] = 0;
      row[P.imageCount] = imgs.list.length;
      row[P.thumb] = enc_(thumbOf_(imgs.list));
      row[P.notice] = false;
      row[P.deleted] = false;
      row[P.device] = enc_(dev.hash);
      row[P.pwHash] = enc_(pw ? hashPassword_(pw, salt) : '');
      row[P.salt] = enc_(salt);
      row[P.images] = enc_(imgs.list);
      row[P.content] = enc_(f.content);
      checkCellSize_(row);
      sh.appendRow(row);
      imgs.attach(id);
      if (!admin) spendSiteLimit_('post');
      return { id: id, no: no, g: g.id };
    });
    if (!admin && CONFIG.POST_COOLDOWN_SEC > 0) cachePut_(cdKey, '1', CONFIG.POST_COOLDOWN_SEC);
    return result;
  });
}

/** 수정 화면용: 비밀번호를 확인하고 원래 내용을 돌려줍니다. */
function apiPostForEdit(req) {
  return run_(function () {
    req = obj_(req);
    const dev = device_(req);
    const admin = isAdmin_(req);
    const found = findById_('posts', int_(req.id));
    if (!found || bool_(found.r[P.deleted])) fail_('삭제되었거나 존재하지 않는 글입니다.');
    const g = gallery_(str_(found.r[P.gallery]), admin);
    withLock_(function () { authorizeEdit_(found.r, req, dev, admin); });
    return {
      gallery: publicGallery_(g, admin),
      post: {
        id: num_(found.r[P.id]),
        g: g.id,
        head: str_(found.r[P.head]),
        title: str_(found.r[P.title]),
        nick: str_(found.r[P.nick]),
        admin: bool_(found.r[P.admin]),
        content: str_(found.r[P.content]),
        images: images_(found.r[P.images]),
      },
    };
  });
}

function apiUpdatePost(req) {
  return run_(function () {
    req = obj_(req);
    const dev = device_(req);
    const admin = isAdmin_(req);
    if (!admin) checkBan_(dev);
    const id = int_(req.id);
    const ids = imageIds_(req.images);
    const load = function () {
      const found = findById_('posts', id);
      if (!found || bool_(found.r[P.deleted])) fail_('삭제되었거나 존재하지 않는 글입니다.');
      const g = gallery_(str_(found.r[P.gallery]), admin);
      authorizeEdit_(found.r, req, dev, admin);
      return { found: found, g: g };
    };
    // 1) 권한부터 확인 (비밀번호 없이는 아래의 사진 공개 설정도 하지 않음)
    const first = withLock_(load);
    const f = postFields_(req, first.g);
    if (!f.content && !ids.length) fail_('내용을 입력해 주세요.');
    // 2) 새로 붙일 사진 공개 (드라이브 호출이 느려서 잠금 밖에서)
    const shared = shareImages_(ids, dev, admin, id);
    // 3) 저장 (잠금 안에서 다시 확인)
    const done = withLock_(function () {
      const found = load().found;
      const imgs = claimImages_(ids, dev, admin, id, shared);
      const keep = {};
      ids.forEach(function (x) { keep[x] = true; });
      const removed = images_(found.r[P.images])
        .filter(function (x) { return !keep[x.id]; })
        .map(function (x) { return x.id; });
      const cells = { head: enc_(f.head), title: enc_(f.title), images: enc_(imgs.list), content: enc_(f.content) };
      checkCellSize_([cells.title, cells.images, cells.content]);
      const sh = sheet_('posts');
      sh.getRange(found.row, P.head + 1, 1, 2).setValues([[cells.head, cells.title]]);
      sh.getRange(found.row, P.updatedAt + 1).setValue(Date.now());
      sh.getRange(found.row, P.imageCount + 1, 1, 2).setValues([[imgs.list.length, enc_(thumbOf_(imgs.list))]]);
      sh.getRange(found.row, P.images + 1, 1, 2).setValues([[cells.images, cells.content]]);
      imgs.attach(id);
      return markImagesDeleted_(removed);
    });
    trashFiles_(done);
    return { id: id, g: first.g.id };
  });
}

function apiDeletePost(req) {
  return run_(function () {
    req = obj_(req);
    const dev = device_(req);
    const admin = isAdmin_(req);
    const done = withLock_(function () {
      const found = findById_('posts', int_(req.id));
      if (!found || bool_(found.r[P.deleted])) fail_('이미 삭제되었거나 존재하지 않는 글입니다.');
      if (!admin) {
        if (bool_(found.r[P.admin])) fail_('관리자 글은 삭제할 수 없습니다.');
        checkPassword_(found.r[P.pwHash], found.r[P.salt], req.pw, dev, 'p' + num_(found.r[P.id]));
      }
      sheet_('posts').getRange(found.row, P.deleted + 1).setValue(true);
      return { g: str_(found.r[P.gallery]), trash: markImagesDeleted_(images_(found.r[P.images]).map(function (x) { return x.id; })) };
    });
    trashFiles_(done.trash);
    return { ok: true, g: done.g };
  });
}

function apiVote(req) {
  return run_(function () {
    req = obj_(req);
    const dev = device_(req);
    const admin = isAdmin_(req);
    if (!admin) checkBan_(dev);
    const type = req.type === 'down' ? 'down' : 'up';
    const id = int_(req.id);
    return withLock_(function () {
      const found = findById_('posts', id);
      if (!found || bool_(found.r[P.deleted])) fail_('삭제되었거나 존재하지 않는 글입니다.');
      gallery_(str_(found.r[P.gallery]), admin);
      if (str_(found.r[P.device]) === dev.hash) fail_('자기 글은 추천·비추천할 수 없습니다.');
      const vs = sheet_('votes');
      const key = id + ':' + dev.hash;
      const last = vs.getLastRow();
      if (last >= 2 && vs.getRange(2, 1, last - 1, 1).createTextFinder(enc_(key)).matchEntireCell(true).matchCase(true).findNext()) {
        fail_('이미 추천 또는 비추천한 글입니다.');
      }
      vs.appendRow([enc_(key), id, enc_(type), Date.now()]);
      const up = num_(found.r[P.up]) + (type === 'up' ? 1 : 0);
      const down = num_(found.r[P.down]) + (type === 'down' ? 1 : 0);
      sheet_('posts').getRange(found.row, (type === 'up' ? P.up : P.down) + 1).setValue(type === 'up' ? up : down);
      return { up: up, down: down };
    });
  });
}

function apiAddComment(req) {
  return run_(function () {
    req = obj_(req);
    const dev = device_(req);
    const admin = isAdmin_(req);
    if (req.hp) fail_('잘못된 요청입니다.');
    if (!admin) checkBan_(dev);
    const postId = int_(req.postId);
    const content = clean_(req.content, { multiline: true });
    if (!content) fail_('댓글 내용을 입력해 주세요.');
    if (content.length > CONFIG.MAX_COMMENT) fail_('댓글은 ' + CONFIG.MAX_COMMENT + '자까지 쓸 수 있습니다.');
    const nick = cleanNick_(req.nick, admin);
    const pw = admin && !req.pw ? '' : cleanPassword_(req.pw);
    const cache = CacheService.getScriptCache();
    const cdKey = 'cd:cmt:' + dev.hash;
    if (!admin) {
      if (cache.get(cdKey)) fail_('도배 방지를 위해 ' + CONFIG.COMMENT_COOLDOWN_SEC + '초에 한 번만 댓글을 쓸 수 있습니다.');
      checkSiteLimit_('comment', CONFIG.SITE_COMMENTS_PER_10MIN, '지금 댓글이 너무 많이 올라와서 잠시 댓글을 막았습니다. 몇 분 뒤에 다시 시도해 주세요.');
    }
    withLock_(function () {
      const found = findById_('posts', postId);
      if (!found || bool_(found.r[P.deleted])) fail_('삭제되었거나 존재하지 않는 글입니다.');
      gallery_(str_(found.r[P.gallery]), admin);
      let parent = int_(req.parentId);
      if (parent) {
        const pc = findById_('comments', parent);
        if (!pc || num_(pc.r[C.postId]) !== postId) fail_('답글을 달 댓글을 찾을 수 없습니다.');
        // 답글의 답글은 원댓글 아래로 모읍니다. (디시처럼 한 단계만)
        if (num_(pc.r[C.parentId])) parent = num_(pc.r[C.parentId]);
        else if (bool_(pc.r[C.deleted])) fail_('삭제된 댓글에는 답글을 달 수 없습니다.');
      }
      const cs = sheet_('comments');
      const id = nextId_(cs, 'LAST_COMMENT_ID');
      const salt = pw ? randomHex_(16) : '';
      const row = [id, postId, parent, enc_(nick), enc_(dev.code), admin, Date.now(), false,
        enc_(dev.hash), enc_(pw ? hashPassword_(pw, salt) : ''), enc_(salt), enc_(content)];
      checkCellSize_(row);
      cs.appendRow(row);
      sheet_('posts').getRange(found.row, P.comments + 1).setValue(num_(found.r[P.comments]) + 1);
      if (!admin) spendSiteLimit_('comment');
    });
    if (!admin && CONFIG.COMMENT_COOLDOWN_SEC > 0) cachePut_(cdKey, '1', CONFIG.COMMENT_COOLDOWN_SEC);
    const comments = commentsOf_(postId);
    return { comments: comments, count: countComments_(comments) };
  });
}

function apiDeleteComment(req) {
  return run_(function () {
    req = obj_(req);
    const dev = device_(req);
    const admin = isAdmin_(req);
    let postId = 0;
    withLock_(function () {
      const found = findById_('comments', int_(req.id));
      if (!found || bool_(found.r[C.deleted])) fail_('이미 삭제되었거나 존재하지 않는 댓글입니다.');
      postId = num_(found.r[C.postId]);
      if (!admin) {
        if (bool_(found.r[C.admin])) fail_('관리자 댓글은 삭제할 수 없습니다.');
        checkPassword_(found.r[C.pwHash], found.r[C.salt], req.pw, dev, 'c' + num_(found.r[C.id]));
      }
      sheet_('comments').getRange(found.row, C.deleted + 1).setValue(true);
      const post = findById_('posts', postId);
      if (post) sheet_('posts').getRange(post.row, P.comments + 1).setValue(Math.max(0, num_(post.r[P.comments]) - 1));
    });
    const comments = commentsOf_(postId);
    return { comments: comments, count: countComments_(comments) };
  });
}

/* ───────────────────────────── 관리자 API ───────────────────────────── */

function apiAdminLogin(req) {
  return run_(function () {
    req = obj_(req);
    const dev = device_(req);
    const cache = CacheService.getScriptCache();
    const mine = 'admfail:' + dev.hash;
    const fails = Number(cache.get(mine) || 0);
    const allFails = Number(cache.get('admfail:all') || 0);
    if (fails >= 5 || allFails >= 50) fail_('로그인 시도가 너무 많습니다. 10분 뒤에 다시 시도해 주세요.');
    const given = normalizeKey_(req.key);
    if (!given || given !== normalizeKey_(props_().ADMIN_KEY)) {
      cachePut_(mine, String(fails + 1), 600);
      cachePut_('admfail:all', String(allFails + 1), 600);
      fail_('관리자 키가 맞지 않습니다.');
    }
    const token = randomHex_(32);
    if (!cachePut_('adm:' + token, adminKeyTag_(), 21600)) fail_('잠시 뒤에 다시 로그인해 주세요.');
    return { token: token, hours: 6 };
  });
}

function apiAdminLogout(req) {
  return run_(function () {
    req = obj_(req);
    if (isToken_(req.admin)) CacheService.getScriptCache().remove('adm:' + req.admin);
    return { ok: true };
  });
}

function apiAdminInfo(req) {
  return run_(function () {
    req = obj_(req);
    requireAdmin_(req);
    const p = props_();
    return {
      owner: isOwner_(),
      sheetUrl: ss_().getUrl(),
      folderUrl: 'https://drive.google.com/drive/folders/' + p.FOLDER_ID,
      galleries: allGalleries_().map(function (g) { return publicGallery_(g, true); }),
      bans: banList_(),
    };
  });
}

function apiAdminNotice(req) {
  return run_(function () {
    req = obj_(req);
    requireAdmin_(req);
    return withLock_(function () {
      const found = findById_('posts', int_(req.id));
      if (!found || bool_(found.r[P.deleted])) fail_('삭제되었거나 존재하지 않는 글입니다.');
      sheet_('posts').getRange(found.row, P.notice + 1).setValue(!!req.on);
      return { notice: !!req.on };
    });
  });
}

/** 글이나 댓글의 작성자(기기)를 차단합니다. days 가 0 이면 영구 차단. */
function apiAdminBan(req) {
  return run_(function () {
    req = obj_(req);
    requireAdmin_(req);
    const isComment = req.kind === 'comment';
    const cols = isComment ? C : P;
    const found = findById_(isComment ? 'comments' : 'posts', int_(req.id));
    if (!found) fail_('대상을 찾을 수 없습니다.');
    if (bool_(found.r[cols.admin])) fail_('관리자는 차단할 수 없습니다.');
    const hash = str_(found.r[cols.device]);
    const code = str_(found.r[cols.code]);
    if (!hash) fail_('차단할 수 없는 대상입니다.');
    const days = Math.max(0, Math.min(3650, Number(req.days) || 0));
    const until = days ? Date.now() + Math.round(days * DAY_MS) : 0;
    const reason = clean_(req.reason).slice(0, 100);
    withLock_(function () {
      const sh = sheet_('bans');
      const last = sh.getLastRow();
      const devices = last >= 2 ? sh.getRange(2, 1, last - 1, 1).getValues() : [];
      let row = 0;
      devices.forEach(function (r, i) { if (str_(r[0]) === hash) row = i + 2; });
      const values = [enc_(hash), enc_(code), until, enc_(reason), Date.now()];
      if (row) sh.getRange(row, 1, 1, values.length).setValues([values]);
      else sh.appendRow(values);
    });
    CacheService.getScriptCache().remove('bans');
    return { code: code, until: until };
  });
}

function apiAdminUnban(req) {
  return run_(function () {
    req = obj_(req);
    requireAdmin_(req);
    const target = String(req.target || '');
    if (!/^[0-9a-f]{16}$/.test(target)) fail_('대상이 올바르지 않습니다.');
    withLock_(function () {
      const sh = sheet_('bans');
      const last = sh.getLastRow();
      if (last < 2) return;
      const devices = sh.getRange(2, 1, last - 1, 1).getValues();
      for (let i = devices.length - 1; i >= 0; i--) {
        if (str_(devices[i][0]).slice(0, 16) === target) sh.deleteRow(i + 2);
      }
    });
    CacheService.getScriptCache().remove('bans');
    return { bans: banList_() };
  });
}

/** 갤러리 만들기(create: true) / 수정. */
function apiAdminSaveGallery(req) {
  return run_(function () {
    req = obj_(req);
    requireAdmin_(req);
    const x = obj_(req.gallery);
    const id = String(x.id || '').trim().toLowerCase();
    if (!/^[a-z][a-z0-9_]{1,19}$/.test(id)) fail_('갤러리 ID는 영문 소문자로 시작하는 2~20자(영문 소문자, 숫자, _)로 정해 주세요.');
    const name = clean_(x.name, { invisible: true });
    if (!name || name.length > 30) fail_('갤러리 이름을 1~30자로 입력해 주세요.');
    const desc = clean_(x.desc).slice(0, 100);
    const seen = {};
    const heads = String(x.heads || '').split(',')
      .map(function (s) { return clean_(s, { invisible: true }).slice(0, 10); })
      .filter(function (s) { if (!s || seen[s]) return false; seen[s] = true; return true; })
      .slice(0, 15);
    const threshold = clampInt_(x.threshold, 1, 1000) || 5;
    const hidden = !!x.hidden;
    withLock_(function () {
      const sh = sheet_('galleries');
      const last = sh.getLastRow();
      const ids = last >= 2 ? sh.getRange(2, 1, last - 1, 1).getValues() : [];
      let row = 0;
      ids.forEach(function (r, i) { if (str_(r[0]).trim() === id) row = i + 2; });
      if (req.create && row) fail_('이미 있는 갤러리 ID입니다.');
      if (!req.create && !row) fail_('존재하지 않는 갤러리입니다.');
      const order = x.order === '' || x.order === null || x.order === undefined ? ids.length + 1 : clampInt_(x.order, -9999, 9999);
      const values = [enc_(name), enc_(desc), enc_(heads.join(',')), threshold, order, hidden];
      if (row) sh.getRange(row, G.name + 1, 1, values.length).setValues([values]);
      else sh.appendRow([enc_(id)].concat(values, [0, Date.now()]));
    });
    CacheService.getScriptCache().remove('galleries');
    return { galleries: allGalleries_().map(function (g) { return publicGallery_(g, true); }) };
  });
}

/* ───────────────────────────── 목록 · 글 · 댓글 내부 로직 ───────────────────────────── */

function listOf_(g, q, metas) {
  q = obj_(q);
  const inGallery = metas.filter(function (m) { return m.g === g.id; });
  const mode = q.mode === 'concept' || q.mode === 'notice' ? q.mode : 'all';
  const head = g.heads.indexOf(String(q.head || '')) >= 0 ? String(q.head) : '';
  const query = clean_(q.q).slice(0, 50);
  const st = ['all', 'title', 'content', 'nick'].indexOf(q.st) >= 0 ? q.st : 'all';
  const showNotices = mode === 'all' && !head && !query;
  let rows = inGallery;
  if (mode === 'concept') rows = rows.filter(function (m) { return m.up >= g.threshold; });
  else if (mode === 'notice') rows = rows.filter(function (m) { return m.notice; });
  else if (showNotices) rows = rows.filter(function (m) { return !m.notice; });
  if (head) rows = rows.filter(function (m) { return m.head === head; });
  if (query) rows = search_(rows, query, st);
  rows.sort(byIdDesc_);
  const size = CONFIG.PAGE_SIZE;
  const pages = Math.max(1, Math.ceil(rows.length / size));
  const page = Math.min(pages, Math.max(1, int_(q.p) || 1));
  const notices = showNotices ? inGallery.filter(function (m) { return m.notice; }).sort(byIdDesc_).slice(0, 10) : [];
  return {
    gallery: publicGallery_(g, false),
    mode: mode,
    head: head,
    q: query,
    st: st,
    page: page,
    pages: pages,
    total: rows.length,
    posts: rows.slice((page - 1) * size, page * size).map(publicMeta_),
    notices: notices.map(publicMeta_),
  };
}

function search_(rows, query, st) {
  const needle = query.toLowerCase();
  const has = function (s) { return String(s).toLowerCase().indexOf(needle) >= 0; };
  if (st === 'title') return rows.filter(function (m) { return has(m.title); });
  if (st === 'nick') return rows.filter(function (m) { return has(m.nick); });
  const hits = contentHits_(query);
  if (st === 'content') return rows.filter(function (m) { return hits[m.row]; });
  return rows.filter(function (m) { return has(m.title) || hits[m.row]; });
}

/** 본문 열에서 검색어가 들어간 행 번호들. */
function contentHits_(query) {
  const sh = sheet_('posts');
  const last = sh.getLastRow();
  const hits = {};
  if (last < 2) return hits;
  // 본문은 JSON 형태로 저장되므로 검색어도 같은 형태로 바꿔 TextFinder 로 후보를 찾고,
  // 줄바꿈(\n) 같은 표기에 잘못 걸린 후보는 실제 본문으로 다시 확인해 걸러 냅니다.
  const needle = JSON.stringify(query).slice(1, -1);
  const rows = sh.getRange(2, P.content + 1, last - 1, 1).createTextFinder(needle).findAll()
    .map(function (cell) { return cell.getRow(); });
  const lower = query.toLowerCase();
  readRows_(sh, rows, 1, P.content + 1).forEach(function (x) {
    if (str_(x.r[0]).toLowerCase().indexOf(lower) >= 0) hits[x.row] = true;
  });
  return hits;
}

function loadMetas_() {
  return readAll_('posts', POST_META_WIDTH)
    .map(function (r, i) { return toMeta_(r, i + 2); })
    .filter(function (m) { return m.id && !m.deleted; });
}

function toMeta_(r, row) {
  return {
    row: row,
    id: num_(r[P.id]),
    g: str_(r[P.gallery]),
    no: num_(r[P.no]),
    head: str_(r[P.head]),
    title: str_(r[P.title]),
    nick: str_(r[P.nick]),
    code: str_(r[P.code]),
    admin: bool_(r[P.admin]),
    at: num_(r[P.createdAt]),
    views: num_(r[P.views]),
    up: num_(r[P.up]),
    down: num_(r[P.down]),
    cmt: num_(r[P.comments]),
    img: num_(r[P.imageCount]),
    thumb: str_(r[P.thumb]),
    notice: bool_(r[P.notice]),
    deleted: bool_(r[P.deleted]),
  };
}

function publicMeta_(m) {
  return {
    id: m.id, g: m.g, no: m.no, head: m.head, title: m.title, nick: m.nick,
    code: m.admin ? '' : m.code, admin: m.admin, at: m.at, views: m.views, up: m.up, down: m.down,
    cmt: m.cmt, img: m.img, thumb: m.thumb, notice: m.notice,
  };
}

function postFields_(req, g) {
  const title = clean_(req.title, { invisible: true });
  if (!title) fail_('제목을 입력해 주세요.');
  if (title.length > CONFIG.MAX_TITLE) fail_('제목은 ' + CONFIG.MAX_TITLE + '자까지 쓸 수 있습니다.');
  const content = clean_(req.content, { multiline: true });
  if (content.length > CONFIG.MAX_CONTENT) fail_('본문은 ' + CONFIG.MAX_CONTENT + '자까지 쓸 수 있습니다.');
  const head = g.heads.indexOf(String(req.head || '')) >= 0 ? String(req.head) : (g.heads[0] || '');
  return { title: title, content: content, head: head };
}

function authorizeEdit_(r, req, dev, admin) {
  if (bool_(r[P.admin])) {
    if (!admin) fail_('관리자 글은 관리자만 수정할 수 있습니다.');
    return;
  }
  checkPassword_(r[P.pwHash], r[P.salt], req.pw, dev, 'p' + num_(r[P.id]));
}

function images_(cell) {
  const v = dec_(cell);
  if (!Array.isArray(v)) return [];
  return v.filter(function (x) { return x && isFileId_(x.id); }).map(function (x) {
    return { id: x.id, mime: String(x.mime || 'image/jpeg'), w: int_(x.w), h: int_(x.h), pub: !!x.pub };
  });
}

function thumbOf_(list) {
  for (let i = 0; i < list.length; i++) {
    if (list[i].pub && list[i].mime !== 'image/gif') return list[i].id;
  }
  return '';
}

function imageIds_(v) {
  if (v === null || v === undefined || v === '') return [];
  if (!Array.isArray(v)) fail_('사진 정보가 올바르지 않습니다.');
  const seen = {};
  const out = [];
  v.forEach(function (id) {
    id = String(id || '');
    if (!isFileId_(id)) fail_('사진 정보가 올바르지 않습니다.');
    if (!seen[id]) { seen[id] = true; out.push(id); }
  });
  if (out.length > CONFIG.MAX_IMAGES) fail_('사진은 글 하나에 ' + CONFIG.MAX_IMAGES + '장까지 올릴 수 있습니다.');
  return out;
}

/**
 * 글에 붙일 사진들을 확인합니다. (읽기만 함)
 * - 새 사진: 아직 어느 글에도 붙지 않았고, 같은 기기가 올린 것만
 * - 수정 중인 글(postId)에 이미 붙어 있던 사진은 그대로 허용
 */
function inspectImages_(ids, dev, admin, postId) {
  if (!ids.length) return [];
  const sh = sheet_('images');
  const last = sh.getLastRow();
  const rowOf = {};
  if (last >= 2) {
    sh.getRange(2, I.id + 1, last - 1, 1).getValues().forEach(function (r, i) { rowOf[str_(r[0])] = i + 2; });
  }
  const missing = '사진을 찾을 수 없습니다. 사진을 다시 첨부해 주세요.';
  const recs = {};
  readRows_(sh, ids.map(function (id) { return rowOf[id] || fail_(missing); }), SCHEMA.images.length)
    .forEach(function (x) { recs[x.row] = x.r; });
  return ids.map(function (id) {
    const row = rowOf[id];
    const r = recs[row];
    if (!r || str_(r[I.id]) !== id) fail_(missing);
    if (bool_(r[I.deleted])) fail_('삭제된 사진이 들어 있습니다. 사진을 다시 첨부해 주세요.');
    const owner = num_(r[I.postId]);
    if (owner) {
      if (owner !== postId) fail_('다른 글에 쓰인 사진은 붙일 수 없습니다.');
    } else if (!admin && str_(r[I.device]) !== dev.hash) {
      fail_('직접 올린 사진만 붙일 수 있습니다.');
    }
    return { id: id, row: row, owner: owner, mime: str_(r[I.mime]), w: num_(r[I.width]), h: num_(r[I.height]), pub: bool_(r[I.public]) };
  });
}

/**
 * 새로 붙일 사진을 "링크가 있는 모든 사용자 보기"로 공개합니다. 드라이브 호출이 느려서 잠금 밖에서 부릅니다.
 * 공유가 막힌 계정(일부 회사·학교 계정)이면 실패하고, 그 사진은 서버를 거쳐 보여 줍니다.
 * 반환: { 파일ID: 공개 성공 여부 }
 */
function shareImages_(ids, dev, admin, postId) {
  const out = {};
  inspectImages_(ids, dev, admin, postId).forEach(function (x) {
    if (x.owner || x.pub) return;
    try {
      DriveApp.getFileById(x.id).setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
      out[x.id] = true;
    } catch (e) {
      out[x.id] = false;
    }
  });
  return out;
}

/** 잠금 안에서: 사진을 다시 확인하고, 글에 저장할 목록과 "글에 붙이기" 함수를 돌려줍니다. */
function claimImages_(ids, dev, admin, postId, shared) {
  shared = shared || {};
  const recs = inspectImages_(ids, dev, admin, postId);
  const list = recs.map(function (x) {
    return { id: x.id, mime: x.mime, w: x.w, h: x.h, pub: shared[x.id] === undefined ? x.pub : shared[x.id] };
  });
  return {
    list: list,
    attach: function (pid) {
      const sh = sheet_('images');
      recs.forEach(function (x, i) {
        if (x.owner) return;
        sh.getRange(x.row, I.postId + 1).setValue(pid);
        if (list[i].pub !== x.pub) sh.getRange(x.row, I.public + 1).setValue(list[i].pub);
      });
    },
  };
}

/** 사진에 삭제 표시를 합니다. (잠금 안에서, 빠름) 표시한 ID 목록을 돌려주니 잠금 밖에서 trashFiles_ 로 지우세요. */
function markImagesDeleted_(ids) {
  if (!ids || !ids.length) return [];
  const sh = sheet_('images');
  const last = sh.getLastRow();
  if (last < 2) return [];
  const want = {};
  ids.forEach(function (id) { want[id] = true; });
  const marked = [];
  sh.getRange(2, I.id + 1, last - 1, 1).getValues().forEach(function (r, i) {
    const id = str_(r[0]);
    if (!want[id]) return;
    delete want[id];
    sh.getRange(i + 2, I.deleted + 1).setValue(true);
    marked.push(id);
  });
  return marked;
}

/** 드라이브 파일을 휴지통으로 보냅니다. (느려서 잠금 밖에서) 휴지통은 30일 뒤 자동으로 비워집니다. */
function trashFiles_(ids) {
  (ids || []).forEach(function (id) {
    try {
      DriveApp.getFileById(id).setTrashed(true);
    } catch (e) {
      console.warn('사진 휴지통 이동 실패: ' + id);
    }
  });
}

function sniffImage_(b) {
  const u = function (i) { return b[i] & 0xff; };
  if (b.length > 3 && u(0) === 0xff && u(1) === 0xd8 && u(2) === 0xff) return 'image/jpeg';
  if (b.length > 8 && u(0) === 0x89 && u(1) === 0x50 && u(2) === 0x4e && u(3) === 0x47) return 'image/png';
  if (b.length > 6 && u(0) === 0x47 && u(1) === 0x49 && u(2) === 0x46 && u(3) === 0x38) return 'image/gif';
  if (b.length > 12 && u(0) === 0x52 && u(1) === 0x49 && u(2) === 0x46 && u(3) === 0x46 &&
    u(8) === 0x57 && u(9) === 0x45 && u(10) === 0x42 && u(11) === 0x50) return 'image/webp';
  return null;
}

function bumpGalleryNo_(gid) {
  const sh = sheet_('galleries');
  const last = sh.getLastRow();
  const ids = last >= 2 ? sh.getRange(2, 1, last - 1, 1).getValues() : [];
  for (let i = 0; i < ids.length; i++) {
    if (str_(ids[i][0]).trim() === gid) {
      const cell = sh.getRange(i + 2, G.lastNo + 1);
      const no = num_(cell.getValue()) + 1;
      cell.setValue(no);
      return no;
    }
  }
  return fail_('존재하지 않는 갤러리입니다.');
}

function commentsOf_(postId) {
  const sh = sheet_('comments');
  const last = sh.getLastRow();
  if (last < 2 || !postId) return [];
  const rows = sh.getRange(2, C.postId + 1, last - 1, 1)
    .createTextFinder(String(postId)).matchEntireCell(true).findAll()
    .map(function (cell) { return cell.getRow(); });
  if (!rows.length) return [];
  const items = readRows_(sh, rows, SCHEMA.comments.length).map(function (x) {
    const r = x.r;
    return {
      id: num_(r[C.id]), postId: num_(r[C.postId]), parent: num_(r[C.parentId]), nick: str_(r[C.nick]),
      code: str_(r[C.code]), admin: bool_(r[C.admin]), at: num_(r[C.createdAt]), deleted: bool_(r[C.deleted]),
      content: str_(r[C.content]),
    };
  }).filter(function (c) { return c.id && c.postId === postId; });
  const replies = {};
  items.forEach(function (c) { if (c.parent) (replies[c.parent] = replies[c.parent] || []).push(c); });
  const out = [];
  items.filter(function (c) { return !c.parent; }).sort(byIdAsc_).forEach(function (top) {
    const kids = (replies[top.id] || []).filter(function (c) { return !c.deleted; }).sort(byIdAsc_);
    if (top.deleted && !kids.length) return; // 답글 없는 삭제 댓글은 숨김
    out.push(publicComment_(top));
    kids.forEach(function (k) { out.push(publicComment_(k)); });
  });
  return out;
}

function publicComment_(c) {
  if (c.deleted) return { id: c.id, parent: c.parent, deleted: true, nick: '', code: '', admin: false, at: c.at, content: '' };
  return { id: c.id, parent: c.parent, deleted: false, nick: c.nick, code: c.admin ? '' : c.code, admin: c.admin, at: c.at, content: c.content };
}

function countComments_(list) {
  return list.filter(function (c) { return !c.deleted; }).length;
}

/* ───────────────────────────── 갤러리 · 차단 ───────────────────────────── */

function allGalleries_() {
  const cache = CacheService.getScriptCache();
  const hit = cache.get('galleries');
  if (hit) {
    try { return JSON.parse(hit); } catch (e) { /* 다시 읽음 */ }
  }
  const list = readAll_('galleries').map(toGallery_).filter(function (g) { return g.id; });
  list.sort(function (a, b) { return a.order - b.order || a.createdAt - b.createdAt; });
  const json = JSON.stringify(list);
  cachePut_('galleries', json, 600);
  return list;
}

function toGallery_(r) {
  const heads = dec_(r[G.heads]);
  const id = str_(r[G.id]).trim();
  return {
    id: id,
    name: str_(r[G.name]).trim() || id,
    desc: str_(r[G.desc]).trim(),
    heads: (Array.isArray(heads) ? heads : String(heads || '').split(','))
      .map(function (s) { return String(s).trim(); })
      .filter(Boolean)
      .slice(0, 20),
    threshold: Math.max(1, Math.round(num_(r[G.threshold])) || 5),
    order: num_(r[G.order]),
    hidden: bool_(r[G.hidden]),
    createdAt: num_(r[G.createdAt]),
  };
}

function publicGallery_(g, admin) {
  const out = { id: g.id, name: g.name, desc: g.desc, heads: g.heads.slice(), threshold: g.threshold };
  if (admin) {
    out.hidden = g.hidden;
    out.order = g.order;
  }
  return out;
}

function gallery_(id, admin) {
  id = String(id || '');
  const g = allGalleries_().filter(function (x) { return x.id === id; })[0];
  if (!g || (g.hidden && !admin)) fail_('존재하지 않는 갤러리입니다.');
  return g;
}

function bans_() {
  const cache = CacheService.getScriptCache();
  const hit = cache.get('bans');
  if (hit) {
    try { return JSON.parse(hit); } catch (e) { /* 다시 읽음 */ }
  }
  const list = readAll_('bans').map(function (r) {
    return { device: str_(r[B.device]), code: str_(r[B.code]), until: num_(r[B.until]), reason: str_(r[B.reason]), at: num_(r[B.createdAt]) };
  }).filter(function (b) { return b.device; });
  const json = JSON.stringify(list);
  cachePut_('bans', json, 300);
  return list;
}

function checkBan_(dev) {
  const now = Date.now();
  const ban = bans_().filter(function (b) { return b.device === dev.hash && (!b.until || b.until > now); })[0];
  if (!ban) return;
  fail_('차단된 사용자입니다' + (ban.until ? ' (' + fmtDate_(ban.until) + '까지)' : ' (영구)') +
    (ban.reason ? ' · 사유: ' + ban.reason : ''));
}

function banList_() {
  const now = Date.now();
  return bans_().map(function (b) {
    return { target: b.device.slice(0, 16), code: b.code, until: b.until, reason: b.reason, at: b.at, active: !b.until || b.until > now };
  }).sort(function (a, b) { return b.at - a.at; });
}

/* ───────────────────────────── 사용자 식별 · 권한 ───────────────────────────── */

/**
 * 웹 앱은 방문자 IP를 알 수 없어서, 브라우저마다 만든 무작위 값(device)을 비밀 값으로 해시해
 * 디시의 "ㅇㅇ(123.45)" 같은 식별코드를 만듭니다.
 */
function device_(req) {
  const raw = req && typeof req.device === 'string' ? req.device : '';
  if (!/^[0-9a-f]{32}$/.test(raw)) fail_('브라우저 식별값이 없습니다. 페이지를 새로고침해 주세요.');
  const h = sha256_(props_().SECRET + '|device|' + raw);
  return { hash: h.slice(0, 40), code: h.slice(0, 4) };
}

function deviceOrNull_(req) {
  try {
    return device_(req);
  } catch (e) {
    return null;
  }
}

let OWNER_MEMO_ = null;
/** 스크립트 주인이 자기 구글 계정으로 접속했는지. (다른 방문자의 이메일은 구글이 알려 주지 않음) */
function isOwner_() {
  if (OWNER_MEMO_ !== null) return OWNER_MEMO_;
  let owner = false;
  try {
    const active = Session.getActiveUser().getEmail();
    const effective = Session.getEffectiveUser().getEmail();
    owner = !!active && !!effective && active.toLowerCase() === effective.toLowerCase();
  } catch (e) {
    owner = false;
  }
  OWNER_MEMO_ = owner;
  return owner;
}

function isAdmin_(req) {
  const token = req && req.admin;
  if (isToken_(token) && CacheService.getScriptCache().get('adm:' + token) === adminKeyTag_()) return true;
  return isOwner_();
}

function requireAdmin_(req) {
  if (!isAdmin_(req)) fail_('관리자 권한이 필요합니다. 다시 로그인해 주세요.');
}

function isToken_(t) {
  return typeof t === 'string' && /^[0-9a-f]{32}$/.test(t);
}

function adminKeyTag_() {
  return sha256_('admin-tag|' + props_().ADMIN_KEY).slice(0, 12);
}

function normalizeKey_(k) {
  return String(k || '').toLowerCase().replace(/[^0-9a-f]/g, '');
}

function newAdminKey_() {
  return randomHex_(24).replace(/(.{4})(?=.)/g, '$1-');
}

/**
 * 비밀번호 확인 (잠금 안에서 호출: 실패 횟수를 정확히 세기 위해)
 * - 기기별: 10번 틀리면 10분 잠금
 * - 대상(글·댓글)별: 20번 틀리면 마지막 실패부터 6시간 잠금. 식별값을 바꿔 가며 맞혀 보는 공격을 막습니다.
 */
function checkPassword_(storedCell, saltCell, pw, dev, target) {
  const cache = CacheService.getScriptCache();
  const devKey = 'pwfail:' + dev.hash;
  const targetKey = 'pwfail:' + target;
  const devFails = Number(cache.get(devKey) || 0);
  const targetFails = Number(cache.get(targetKey) || 0);
  if (devFails >= 10) fail_('비밀번호를 너무 많이 틀렸습니다. 10분 뒤에 다시 시도해 주세요.');
  if (targetFails >= 20) fail_('비밀번호를 틀린 횟수가 많아 지금은 수정·삭제할 수 없습니다. 몇 시간 뒤에 다시 시도하거나 관리자에게 문의해 주세요.');
  const stored = str_(storedCell);
  if (!stored || hashPassword_(pw === null || pw === undefined ? '' : String(pw), str_(saltCell)) !== stored) {
    cachePut_(devKey, String(devFails + 1), 600);
    cachePut_(targetKey, String(targetFails + 1), 21600);
    fail_('비밀번호가 맞지 않습니다.');
  }
}

function hashPassword_(pw, salt) {
  let h = sha256_(props_().SECRET + '|pw|' + salt + '|' + pw);
  for (let i = 0; i < 64; i++) h = sha256_(h + '|' + salt);
  return h;
}

/** 같은 기기의 횟수 제한. seconds 단위의 고정 구간마다 limit 번까지. */
function hit_(key, limit, seconds, message) {
  const cache = CacheService.getScriptCache();
  const k = key + ':' + Math.floor(Date.now() / (seconds * 1000));
  const n = Number(cache.get(k) || 0);
  if (n >= limit) fail_(message);
  cachePut_(k, String(n + 1), seconds);
}

function siteLimitKey_(name) {
  return 'site:' + name + ':' + Math.floor(Date.now() / 600000);
}

/** 사이트 전체 10분당 한도. 넘으면 오류. (실제로 쓴 뒤 spendSiteLimit_ 로 셉니다) */
function checkSiteLimit_(name, limit, message) {
  if (!(limit > 0)) return;
  if (Number(CacheService.getScriptCache().get(siteLimitKey_(name)) || 0) >= limit) fail_(message);
}

function spendSiteLimit_(name) {
  const key = siteLimitKey_(name);
  cachePut_(key, String(Number(CacheService.getScriptCache().get(key) || 0) + 1), 600);
}

/** 최근 24시간 동안 사이트 전체에 올라온 사진 수·용량 한도. (사진 목록의 끝부분만 읽음) */
function checkUploadBudget_(newBytes) {
  const sh = sheet_('images');
  const last = sh.getLastRow();
  if (last < 2) return;
  const since = Date.now() - DAY_MS;
  const take = Math.min(last - 1, CONFIG.SITE_UPLOADS_PER_DAY + 1);
  const width = I.createdAt - I.bytes + 1;
  const rows = sh.getRange(last - take + 1, I.bytes + 1, take, width).getValues();
  let count = 0;
  let bytes = newBytes;
  for (let i = rows.length - 1; i >= 0; i--) {
    if (num_(rows[i][width - 1]) < since) break;
    count++;
    bytes += num_(rows[i][0]);
  }
  if (count >= CONFIG.SITE_UPLOADS_PER_DAY || bytes > CONFIG.SITE_UPLOAD_MB_PER_DAY * 1024 * 1024) {
    fail_('오늘은 사이트 전체의 사진 업로드 한도에 도달했습니다. 내일 다시 시도해 주세요.');
  }
}

/**
 * CacheService.put 을 감쌉니다. 실패해도(용량 초과 등) 기능은 계속 동작합니다.
 * 값 한도는 100KB(바이트)이고 한글은 글자당 3바이트라서 3만 자가 넘으면 저장하지 않습니다.
 */
function cachePut_(key, value, seconds) {
  value = String(value);
  if (value.length > 30000) return false;
  try {
    CacheService.getScriptCache().put(key, value, Math.max(1, Math.min(21600, Math.round(seconds))));
    return true;
  } catch (e) {
    return false;
  }
}

/* ───────────────────────────── 입력값 정리 ───────────────────────────── */

const CONTROL_CHARS_ = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F\u202A-\u202E\u2066-\u2069]/g;
const INVISIBLE_CHARS_ = /[\u00AD\u115F\u1160\u200B-\u200F\u2060-\u2064\u3164\uFEFF\uFFA0]/g;

function clean_(value, opts) {
  opts = opts || {};
  let s = value === null || value === undefined ? '' : String(value);
  if (s.length > 200000) fail_('입력한 내용이 너무 깁니다.');
  s = s.replace(/\r\n?/g, '\n')
    .replace(CONTROL_CHARS_, '')
    .replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])/g, '')
    .replace(/(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g, '');
  if (opts.invisible) s = s.replace(INVISIBLE_CHARS_, '');
  s = opts.multiline ? s.replace(/[ \t]+$/gm, '') : s.replace(/\s+/g, ' ');
  return s.trim();
}

function cleanNick_(nick, admin) {
  let s = clean_(nick, { invisible: true });
  if (!s) s = admin ? '관리자' : CONFIG.DEFAULT_NICK;
  if (s.length > CONFIG.MAX_NICK) fail_('닉네임은 ' + CONFIG.MAX_NICK + '자까지 쓸 수 있습니다.');
  if (!admin) {
    const flat = s.toLowerCase().replace(/\s/g, '');
    const bad = CONFIG.RESERVED_NICKS.some(function (w) { return flat.indexOf(String(w).toLowerCase()) >= 0; });
    if (bad) fail_('사용할 수 없는 닉네임입니다.');
  }
  return s;
}

function cleanPassword_(pw) {
  const s = pw === null || pw === undefined ? '' : String(pw);
  if (s.length < CONFIG.MIN_PASSWORD) fail_('비밀번호를 ' + CONFIG.MIN_PASSWORD + '자 이상 입력해 주세요.');
  if (s.length > CONFIG.MAX_PASSWORD) fail_('비밀번호는 ' + CONFIG.MAX_PASSWORD + '자까지 쓸 수 있습니다.');
  return s;
}

function cleanParams_(p) {
  const out = {};
  if (!p) return out;
  ['g', 'id', 'p', 'mode', 'head', 'st', 'q', 'write', 'admin'].forEach(function (k) {
    if (p[k] !== undefined && p[k] !== null && p[k] !== '') out[k] = String(p[k]).slice(0, 100);
  });
  return out;
}

function checkCellSize_(values) {
  values.forEach(function (v) {
    if (typeof v === 'string' && v.length > 49000) fail_('내용이 너무 깁니다. 조금 줄여 주세요.');
  });
}

function isFileId_(id) {
  return typeof id === 'string' && /^[A-Za-z0-9_-]{10,128}$/.test(id);
}

/* ───────────────────────────── 저장소 (스프레드시트 · 드라이브) ───────────────────────────── */

let PROPS_ = null;
function props_() {
  if (PROPS_) return PROPS_;
  const cache = CacheService.getScriptCache();
  const hit = cache.get('props');
  if (hit) {
    try {
      PROPS_ = JSON.parse(hit);
      return PROPS_;
    } catch (e) { /* 다시 읽음 */ }
  }
  // 스크립트 속성은 하루 읽기 횟수 제한이 있어서 캐시에 6시간 보관합니다.
  PROPS_ = PropertiesService.getScriptProperties().getProperties();
  if (isReady_(PROPS_)) cachePut_('props', JSON.stringify(PROPS_), 21600);
  return PROPS_;
}

function resetPropsCache_() {
  PROPS_ = null;
  CacheService.getScriptCache().remove('props');
}

function isReady_(p) {
  return !!(p && p.SPREADSHEET_ID && p.FOLDER_ID && p.IMAGE_FOLDER_ID && p.SECRET && p.ADMIN_KEY);
}

/**
 * 데이터 스프레드시트와 사진 폴더를 준비합니다. 없을 때만 새로 만들고,
 * 있는데 열 수 없으면(일시 오류 등) 새로 만들지 않고 오류를 냅니다. (데이터가 갈라지지 않게)
 */
function ensureReady_() {
  if (isReady_(props_())) return props_();
  return withLock_(function () {
    resetPropsCache_();
    const sp = PropertiesService.getScriptProperties();
    const cur = sp.getProperties();
    const next = {};
    let root = null;
    if (cur.FOLDER_ID) {
      root = openFolder_(cur.FOLDER_ID);
    } else {
      root = DriveApp.createFolder(CONFIG.SITE_NAME + ' 데이터');
      next.FOLDER_ID = root.getId();
    }
    if (cur.IMAGE_FOLDER_ID) openFolder_(cur.IMAGE_FOLDER_ID);
    else next.IMAGE_FOLDER_ID = root.createFolder('사진').getId();
    let ss = null;
    if (cur.SPREADSHEET_ID) {
      ss = openSpreadsheet_(cur.SPREADSHEET_ID);
    } else {
      ss = SpreadsheetApp.create(CONFIG.SITE_NAME + ' DB');
      next.SPREADSHEET_ID = ss.getId();
      try {
        DriveApp.getFileById(ss.getId()).moveTo(root);
      } catch (e) { /* 내 드라이브 최상위에 남아도 동작에는 문제 없음 */ }
    }
    if (!cur.SECRET) next.SECRET = randomHex_(48);
    if (!cur.ADMIN_KEY) next.ADMIN_KEY = newAdminKey_();
    if (Object.keys(next).length) sp.setProperties(next);
    resetPropsCache_();
    DB_ = null;
    ensureSchema_(ss, !!next.SPREADSHEET_ID);
    return props_();
  }, 30000);
}

function openFolder_(id) {
  try {
    return DriveApp.getFolderById(id);
  } catch (e) {
    resetPropsCache_();
    return fail_('데이터 폴더를 열 수 없습니다. 폴더를 지웠다면 스크립트 속성에서 FOLDER_ID / IMAGE_FOLDER_ID 를 지우고 setup()을 다시 실행하세요.');
  }
}

function openSpreadsheet_(id) {
  try {
    return SpreadsheetApp.openById(id);
  } catch (e) {
    resetPropsCache_();
    return fail_('데이터 스프레드시트를 열 수 없습니다. 시트를 지웠다면 스크립트 속성에서 SPREADSHEET_ID 를 지우고 setup()을 다시 실행하세요.');
  }
}

function ensureSchema_(ss, fresh) {
  const before = ss.getSheets();
  Object.keys(SCHEMA).forEach(function (name) {
    initSheet_(ss.getSheetByName(name) || ss.insertSheet(name), name);
  });
  // 방금 만든 스프레드시트에 기본으로 생기는 빈 시트(Sheet1, 시트1)만 정리합니다.
  if (fresh) {
    before.forEach(function (sh) {
      if (!Object.prototype.hasOwnProperty.call(SCHEMA, sh.getName()) && sh.getLastRow() === 0) ss.deleteSheet(sh);
    });
  }
  seed_(ss);
}

function initSheet_(sh, name) {
  const width = SCHEMA[name].length;
  if (sh.getLastRow() === 0) {
    sh.getRange(1, 1, 1, width).setValues([SCHEMA[name]]).setFontWeight('bold');
    sh.setFrozenRows(1);
  }
  // 새 시트는 열이 26개라서, 안 쓰는 열을 지워 스프레드시트 셀 한도(1천만 개)를 아낍니다.
  const max = sh.getMaxColumns();
  if (max > width && sh.getLastColumn() <= width) sh.deleteColumns(width + 1, max - width);
}

function seed_(ss) {
  const gs = ss.getSheetByName('galleries');
  if (gs.getLastRow() < 2 && CONFIG.GALLERIES.length) {
    const now = Date.now();
    const rows = CONFIG.GALLERIES.map(function (g, i) {
      return [enc_(g.id), enc_(g.name), enc_(g.desc || ''), enc_((g.heads || []).join(',')), Number(g.threshold) || 5, i + 1, false, 0, now];
    });
    gs.getRange(2, 1, rows.length, SCHEMA.galleries.length).setValues(rows);
    CacheService.getScriptCache().remove('galleries');
  }
  const ps = ss.getSheetByName('posts');
  if (ps.getLastRow() < 2 && CONFIG.GALLERIES.length) {
    const g = CONFIG.GALLERIES[0];
    const now = Date.now();
    const row = new Array(SCHEMA.posts.length).fill('');
    row[P.id] = 1;
    row[P.gallery] = enc_(g.id);
    row[P.no] = 1;
    row[P.head] = enc_((g.heads || [])[0] || '');
    row[P.title] = enc_(CONFIG.SITE_NAME + ' 이용 안내');
    row[P.nick] = enc_('관리자');
    row[P.code] = enc_('');
    row[P.admin] = true;
    row[P.createdAt] = now;
    row[P.updatedAt] = now;
    row[P.views] = 0;
    row[P.up] = 0;
    row[P.down] = 0;
    row[P.comments] = 0;
    row[P.imageCount] = 0;
    row[P.thumb] = enc_('');
    row[P.notice] = true;
    row[P.deleted] = false;
    row[P.device] = enc_('');
    row[P.pwHash] = enc_('');
    row[P.salt] = enc_('');
    row[P.images] = enc_([]);
    row[P.content] = enc_(welcomeText_(g));
    ps.appendRow(row);
    const gl = ss.getSheetByName('galleries');
    const last = gl.getLastRow();
    if (last >= 2) {
      gl.getRange(2, 1, last - 1, 1).getValues().forEach(function (r, i) {
        if (str_(r[0]).trim() === g.id) gl.getRange(i + 2, G.lastNo + 1).setValue(1);
      });
    }
  }
}

function welcomeText_(g) {
  return [
    CONFIG.SITE_NAME + '에 오신 것을 환영합니다!',
    '',
    '■ 글쓰기',
    '· 닉네임과 비밀번호만 입력하면 누구나 글을 쓸 수 있습니다. (닉네임을 비우면 "' + CONFIG.DEFAULT_NICK + '")',
    '· 비밀번호는 글 수정·삭제에 필요하니 꼭 기억해 두세요.',
    '· [사진 첨부] 버튼, 드래그 앤 드롭, 붙여넣기(Ctrl+V)로 사진을 올릴 수 있습니다. (글 하나에 최대 ' + CONFIG.MAX_IMAGES + '장)',
    '',
    '■ 추천과 개념글',
    '· 추천을 일정 수 이상 받은 글은 개념글이 됩니다. (이 갤러리는 ' + (Number(g.threshold) || 5) + '개)',
    '· 최근 ' + CONFIG.BEST_HOURS + '시간 동안 추천을 많이 받은 글은 첫 화면 "실시간 베스트"에 올라갑니다.',
    '',
    '■ 식별코드',
    '· 닉네임 옆 괄호 안의 코드는 기기(브라우저)마다 다르게 붙는 식별코드입니다. 같은 사람이 쓴 글인지 구분할 때 참고하세요.',
    '',
    '■ 이용 규칙',
    '· 욕설, 도배, 개인정보 노출, 불법 촬영물 등은 예고 없이 삭제되고 작성자는 차단될 수 있습니다.',
  ].join('\n');
}

function installTriggers_() {
  const exists = ScriptApp.getProjectTriggers().some(function (t) { return t.getHandlerFunction() === 'cleanupOrphanImages'; });
  if (!exists) ScriptApp.newTrigger('cleanupOrphanImages').timeBased().everyHours(6).create();
}

let DB_ = null;
function ss_() {
  if (DB_) return DB_.ss;
  const p = ensureReady_();
  DB_ = { ss: openSpreadsheet_(p.SPREADSHEET_ID), sheets: {} };
  return DB_.ss;
}

function sheet_(name) {
  const ss = ss_();
  if (DB_.sheets[name]) return DB_.sheets[name];
  let sh = ss.getSheetByName(name);
  if (!sh) {
    // 누군가 시트를 지웠으면 빈 시트로 다시 만듭니다.
    withLock_(function () {
      sh = ss.getSheetByName(name) || ss.insertSheet(name);
      initSheet_(sh, name);
    });
  }
  DB_.sheets[name] = sh;
  return sh;
}

function imageFolder_() {
  return openFolder_(ensureReady_().IMAGE_FOLDER_ID);
}

function readAll_(name, width) {
  const sh = sheet_(name);
  const last = sh.getLastRow();
  if (last < 2) return [];
  return sh.getRange(2, 1, last - 1, width || SCHEMA[name].length).getValues();
}

/** 여러 행을 읽되, 가까이 붙어 있는 행은 한 번에 묶어서 읽습니다. */
function readRows_(sh, rowNums, width, startCol) {
  const seen = {};
  const rows = rowNums.filter(function (r) {
    if (seen[r]) return false;
    seen[r] = true;
    return true;
  }).sort(function (a, b) { return a - b; });
  const out = [];
  let i = 0;
  while (i < rows.length) {
    let j = i;
    while (j + 1 < rows.length && rows[j + 1] - rows[j] <= 25) j++;
    const start = rows[i];
    const block = sh.getRange(start, startCol || 1, rows[j] - start + 1, width).getValues();
    for (let k = i; k <= j; k++) out.push({ row: rows[k], r: block[rows[k] - start] });
    i = j + 1;
  }
  return out;
}

/** id 로 행을 찾습니다. 보통은 (id + 1)번째 행이라 바로 찾고, 아니면 TextFinder 로 찾습니다. */
function findById_(name, id) {
  if (!(id >= 1) || Math.floor(id) !== id) return null;
  const sh = sheet_(name);
  const last = sh.getLastRow();
  if (last < 2) return null;
  const width = SCHEMA[name].length;
  let row = id + 1;
  if (row <= last) {
    const r = sh.getRange(row, 1, 1, width).getValues()[0];
    if (num_(r[0]) === id) return { row: row, r: r };
  }
  const cell = sh.getRange(2, 1, last - 1, 1).createTextFinder(String(id)).matchEntireCell(true).findNext();
  if (!cell) return null;
  row = cell.getRow();
  const r = sh.getRange(row, 1, 1, width).getValues()[0];
  return num_(r[0]) === id ? { row: row, r: r } : null;
}

/**
 * 새 번호 = max(시트 마지막 행 번호, 저장해 둔 마지막 번호) + 1. (잠금 안에서 호출)
 * 누가 시트에서 마지막 행을 지워도 번호가 다시 쓰이지 않아, 옛 댓글·추천이 새 글에 붙지 않습니다.
 */
function nextId_(sh, counterKey) {
  const sp = PropertiesService.getScriptProperties();
  const last = sh.getLastRow();
  const fromSheet = last < 2 ? 0 : Math.max(num_(sh.getRange(last, 1).getValue()), last - 1);
  const id = Math.max(fromSheet, Number(sp.getProperty(counterKey)) || 0) + 1;
  sp.setProperty(counterKey, String(id));
  return id;
}

let LOCK_DEPTH_ = 0;
/** 동시에 여러 명이 쓸 때 번호가 겹치지 않도록 스크립트 잠금 안에서 실행합니다. (중첩 호출 가능) */
function withLock_(fn, timeoutMs) {
  const lock = LockService.getScriptLock();
  if (LOCK_DEPTH_ === 0 && !lock.tryLock(timeoutMs || 15000)) {
    fail_('사용자가 많아 처리가 늦어지고 있습니다. 잠시 뒤에 다시 시도해 주세요.');
  }
  LOCK_DEPTH_++;
  try {
    return fn();
  } finally {
    LOCK_DEPTH_--;
    if (LOCK_DEPTH_ === 0) {
      SpreadsheetApp.flush();
      lock.releaseLock();
    }
  }
}

/* ───────────────────────────── 값 변환 · 공통 도구 ───────────────────────────── */

/**
 * 스프레드시트는 "=..." 를 수식으로, "1/2" 를 날짜로, "007" 을 숫자로 바꿔 버립니다.
 * 그래서 문자열·배열은 항상 JSON 형태("..." 또는 [...])로 저장하고, 읽을 때 되돌립니다.
 */
function enc_(v) {
  if (v === null || v === undefined) return '';
  if (typeof v === 'number' || typeof v === 'boolean') return v;
  return JSON.stringify(v);
}

function dec_(v) {
  if (Object.prototype.toString.call(v) === '[object Date]') return v.getTime();
  if (typeof v !== 'string' || v === '') return v;
  const c = v.charAt(0);
  if (c === '"' || c === '[' || c === '{') {
    try {
      return JSON.parse(v);
    } catch (e) {
      return v;
    }
  }
  return v;
}

function str_(v) {
  v = dec_(v);
  if (v === null || v === undefined) return '';
  return typeof v === 'string' ? v : String(v);
}

function num_(v) {
  v = dec_(v);
  const n = Number(v);
  return isFinite(n) ? n : 0;
}

function bool_(v) {
  v = dec_(v);
  return v === true || v === 1 || v === 'TRUE' || v === 'true' || v === '1';
}

function int_(v) {
  const n = Math.floor(Number(v));
  return isFinite(n) ? n : 0;
}

function clampInt_(v, min, max) {
  return Math.min(max, Math.max(min, int_(v)));
}

function obj_(v) {
  return v && typeof v === 'object' && !Array.isArray(v) ? v : {};
}

function byIdDesc_(a, b) { return b.id - a.id; }
function byIdAsc_(a, b) { return a.id - b.id; }

function sha256_(s) {
  const bytes = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, s, Utilities.Charset.UTF_8);
  let out = '';
  for (let i = 0; i < bytes.length; i++) {
    const b = bytes[i] & 0xff;
    out += (b < 16 ? '0' : '') + b.toString(16);
  }
  return out;
}

function randomHex_(n) {
  let s = '';
  while (s.length < n) s += sha256_(Utilities.getUuid() + '|' + Date.now() + '|' + Math.random());
  return s.slice(0, n);
}

function startOfToday_() {
  const now = new Date();
  const ymd = Utilities.formatDate(now, CONFIG.TIME_ZONE, 'yyyy-MM-dd').split('-');
  const offset = Utilities.formatDate(now, CONFIG.TIME_ZONE, 'Z'); // 예: +0900
  const sign = offset.charAt(0) === '-' ? -1 : 1;
  const minutes = sign * (Number(offset.substr(1, 2)) * 60 + Number(offset.substr(3, 2)));
  return Date.UTC(Number(ymd[0]), Number(ymd[1]) - 1, Number(ymd[2])) - minutes * 60000;
}

function fmtDate_(ms) {
  return Utilities.formatDate(new Date(ms), CONFIG.TIME_ZONE, 'yyyy.MM.dd HH:mm');
}

function webAppUrl_() {
  if (CONFIG.WEB_APP_URL) return CONFIG.WEB_APP_URL;
  try {
    return ScriptApp.getService().getUrl() || '';
  } catch (e) {
    return '';
  }
}

function safeJson_(o) {
  return JSON.stringify(o)
    .replace(/</g, '\\u003c')
    .replace(/>/g, '\\u003e')
    .replace(/&/g, '\\u0026')
    .replace(/\u2028/g, '\\u2028')
    .replace(/\u2029/g, '\\u2029');
}

/* ───────────────────────────── 오류 처리 ───────────────────────────── */

class UserError_ extends Error {}

function fail_(message) {
  throw new UserError_(message);
}

/** 사용자에게 보여 줄 오류는 그대로, 예상 못 한 오류는 로그를 남기고 짧게 알려 줍니다. */
function run_(fn) {
  try {
    return fn();
  } catch (e) {
    if (e instanceof UserError_) throw new Error(e.message);
    console.error(e && e.stack ? e.stack : e);
    throw new Error('서버 오류가 발생했습니다. 잠시 뒤에 다시 시도해 주세요. (' + (e && e.message ? e.message : e) + ')');
  }
}
