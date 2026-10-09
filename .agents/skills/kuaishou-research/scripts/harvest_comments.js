async () => {
  const sleep = (ms) => new Promise(r => setTimeout(r, ms));

  // 0. 立刻暂停视频，避免连播把页面切走
  try {
    const v = document.querySelector('video');
    if (v) { v.pause(); v.loop = true; v.autoplay = false; }
  } catch (e) {}
  // 关掉连播开关（若存在）
  try {
    const sw = document.querySelector('[role=switch][aria-checked=true]');
    if (sw) sw.click();
  } catch (e) {}

  // 1. 找评论滚动容器（真正可滚的是评论容器的一个祖先）
  const pickScroller = () => {
    const root = document.querySelector('.comment-container');
    if (!root) return null;
    const chain = [];
    let e = root;
    for (let i = 0; i < 6 && e; i++) { chain.push(e); e = e.parentElement; }
    const scrollers = chain.filter(el => el.scrollHeight > el.clientHeight + 80);
    scrollers.sort((a, b) => (b.scrollHeight - b.clientHeight) - (a.scrollHeight - a.clientHeight));
    return scrollers[0] || null;
  };

  const scroller = pickScroller();
  if (scroller) {
    for (let i = 0; i < 18; i++) {
      scroller.scrollTop += 1000;
      scroller.dispatchEvent(new Event('scroll', { bubbles: true }));
      await sleep(600);
      try { const v = document.querySelector('video'); if (v && !v.paused) v.pause(); } catch (e) {}
    }
    scroller.scrollTop = 0;
    await sleep(400);
    for (let i = 0; i < 18; i++) {
      scroller.scrollTop += 900;
      scroller.dispatchEvent(new Event('scroll', { bubbles: true }));
      await sleep(450);
    }
  }

  // 2. 结构化提取评论
  const TS = /(刚刚|(\d+)\s*(秒|分钟|小时|天|周|个?月|年)前)/;
  const container = document.querySelector('.comment-container') || document.body;
  const lines = (container.innerText || '').split('\n').map(s => s.trim()).filter(Boolean);
  const comments = [];
  const skip = new Set(['取消', '发送', '查看更多回复', '展开更多', '收起']);
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.length < 40 && TS.test(line) && !skip.has(line)) {
      const user = line.replace(TS, '').trim() || line;
      let content = '';
      for (let j = i + 1; j < Math.min(i + 4, lines.length); j++) {
        const cand = lines[j];
        if (skip.has(cand)) continue;
        if (TS.test(cand) && cand.length < 40) break;
        if (/^\d+$/.test(cand)) continue;
        if (cand.length >= 2) { content = cand; break; }
      }
      if (content) comments.push({ user, content });
    }
  }

  return {
    url: location.href,
    title: document.title,
    scrollerFound: !!scroller,
    extracted: comments.length,
    comments
  };
}
