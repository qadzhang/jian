// 渲染逻辑(固定,不需要改)
function esc(s){ return String(s).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;"); }
function renderModules(){
  const grid = document.getElementById("module-grid");
  grid.innerHTML = MODULES.map(m => `
    <article class="module-card" id="${m.id}" data-search="${esc(m.name+' '+m.py+' '+m.desc+' '+m.deps)}">
      <div class="mc-head">
        <span class="mc-name">${m.name}</span>
        <span class="tag ${m.color}">${m.phase}</span>
      </div>
      <div class="mc-desc">${m.desc}</div>
      <div class="mc-meta">
        <span class="tag gray">对标 ${m.py}</span>
      </div>
      <div class="mc-stats">
        <div><b>${m.methods}</b>方法/能力</div>
        <div><b>${m.lines}</b>代码量</div>
      </div>
      <div class="mc-dep">依赖:${m.deps}</div>
    </article>
  `).join("");
}

// ════════════════════════════════════════════════════════════
// 渲染:API 速查(分组)
// ════════════════════════════════════════════════════════════
function renderApiQuick(){
  const root = document.getElementById("api-quick-grid");
  root.innerHTML = API_QUICK.map(g => `
    <div data-search="${esc(g.group+' '+g.items.map(i=>i.sig).join(' '))}">
      <h3 style="margin-top:24px">${g.group}</h3>
      <div class="tbl-wrap">
        <table>
          <thead><tr><th>签名</th><th>模块</th><th>状态</th></tr></thead>
          <tbody>
            ${g.items.map(it => `
              <tr>
                <td><code>${esc(it.sig)}</code></td>
                <td>${it.mod}</td>
                <td><span class="tag gray">${it.status}</span></td>
              </tr>
            `).join("")}
          </tbody>
        </table>
      </div>
    </div>
  `).join("");
}

// ════════════════════════════════════════════════════════════
// 渲染:方法目录(API 参考卡)
// ════════════════════════════════════════════════════════════
function statusTag(s){
  const map = { planned:["gray","planned 规划"], alpha:["amber","alpha 开发"], beta:["purple","beta 测试"], stable:["green","stable 稳定"] };
  const [c,label] = map[s] || ["gray",s];
  return `<span class="tag ${c}">${label}</span>`;
}
function renderApiRef(){
  const root = document.getElementById("api-ref-list");
  if(!API_REF.length){
    root.innerHTML = `<div class="api-placeholder">方法目录将随代码实现持续补充。模板见下方。</div>`;
    return;
  }
  root.innerHTML = API_REF.map(m => {
    const paramRows = m.params.length
      ? m.params.map(p => `<tr><td><code>${esc(p.name)}</code></td><td>${esc(p.type)}</td><td>${esc(p.desc)}</td></tr>`).join("")
      : `<tr><td colspan="3" style="color:var(--fg-mute)">无参数</td></tr>`;
    const throwList = m.throws.length ? `<ul>${m.throws.map(t=>`<li>${esc(t)}</li>`).join("")}</ul>` : `<span style="color:var(--fg-mute)">无</span>`;
    return `
      <article class="method-card" id="${m.id}" data-search="${esc(m.module+' '+m.sig+' '+m.summary+' '+(m.params.map(p=>p.name).join(' ')))}">
        <div class="mc-id">
          <code class="mc-sig">${esc(m.sig)}</code>
          <span class="tag blue">${m.module}</span>
          ${statusTag(m.status)}
          <span class="tag gray">${m.since}</span>
        </div>
        <div class="mc-summary">${m.summary}</div>
        <div class="mc-section">
          <div class="mc-section-title">参数</div>
          <div class="tbl-wrap"><table class="mc-params">
            <thead><tr><th>参数</th><th>类型</th><th>说明</th></tr></thead>
            <tbody>${paramRows}</tbody>
          </table></div>
        </div>
        <div class="mc-section">
          <div class="mc-section-title">返回</div>
          <div><code>${esc(m.returns.type)}</code> — ${esc(m.returns.desc)}</div>
        </div>
        <div class="mc-section">
          <div class="mc-section-title">示例</div>
          <pre><code>${esc(m.example)}</code></pre>
        </div>
        <div class="mc-section">
          <div class="mc-section-title">可能抛出</div>
          <div style="font-size:13px">${throwList}</div>
        </div>
      </article>
    `;
  }).join("");
}

// ════════════════════════════════════════════════════════════
// 主题切换(记忆到 localStorage)
// ════════════════════════════════════════════════════════════
// 安全访问 localStorage(隐私模式 / file:// 沙箱下可能不可用,降级为内存变量)
const safeStore = (() => {
  let mem = {};
  try {
    const k = "__jian_test__";
    localStorage.setItem(k, "1"); localStorage.removeItem(k);
    return { get: k => { try { return localStorage.getItem(k); } catch(e){ return mem[k]; } },
             set: (k,v) => { try { localStorage.setItem(k,v); } catch(e){ mem[k]=v; } } };
  } catch(e) {
    return { get: k => mem[k], set: (k,v) => { mem[k]=v; } };
  }
})();
function initTheme(){
  const saved = safeStore.get("jian-theme");
  const preferDark = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
  const theme = saved || (preferDark ? "dark" : "light");
  applyTheme(theme);
  document.getElementById("theme-toggle").addEventListener("click", () => {
    const cur = document.documentElement.getAttribute("data-theme") || "light";
    applyTheme(cur === "dark" ? "light" : "dark");
  });
}
function applyTheme(t){
  document.documentElement.setAttribute("data-theme", t);
  safeStore.set("jian-theme", t);
  document.getElementById("theme-icon").textContent = t === "dark" ? "☀️" : "🌙";
}

// ════════════════════════════════════════════════════════════
// 实时搜索(过滤模块卡 + API 速查 + 方法卡)
// ════════════════════════════════════════════════════════════
function initSearch(){
  const input = document.getElementById("search");
  let timer;
  input.addEventListener("input", () => {
    clearTimeout(timer);
    timer = setTimeout(doSearch, 120);
  });
  input.addEventListener("keydown", e => {
    if(e.key === "Escape"){ input.value=""; doSearch(); }
  });
}
function doSearch(){
  const q = document.getElementById("search").value.trim().toLowerCase();
  // 收集所有可搜索块
  const blocks = [
    ...document.querySelectorAll(".module-card"),
    ...document.querySelectorAll("#api-quick-grid > div"),
    ...document.querySelectorAll(".method-card")
  ];
  blocks.forEach(el => {
    const hay = (el.getAttribute("data-search") || "") + " " + el.textContent.toLowerCase();
    if(!q || hay.toLowerCase().includes(q)){
      el.classList.remove("hidden");
    } else {
      el.classList.add("hidden");
    }
  });
}

// ════════════════════════════════════════════════════════════
// 导航高亮(滚动联动)
// ════════════════════════════════════════════════════════════
function initNavHighlight(){
  const links = [...document.querySelectorAll(".sidebar a")];
  const targets = links.map(a => document.querySelector(a.getAttribute("href"))).filter(Boolean);
  // 能力探测:不支持 IntersectionObserver 的环境(老浏览器/沙箱)降级为点击高亮,不崩溃
  if(!("IntersectionObserver" in window)){
    links.forEach(l => l.addEventListener("click", () => {
      links.forEach(x => x.classList.remove("active"));
      l.classList.add("active");
    }));
    return;
  }
  const obs = new IntersectionObserver(entries => {
    entries.forEach(e => {
      if(e.isIntersecting){
        links.forEach(l => l.classList.remove("active"));
        const link = document.querySelector(`.sidebar a[href="#${e.target.id}"]`);
        if(link) link.classList.add("active");
      }
    });
  }, { rootMargin: "-80px 0px -70% 0px" });
  targets.forEach(t => obs.observe(t));
}

// ════════════════════════════════════════════════════════════
// 启动
// ════════════════════════════════════════════════════════════
document.addEventListener("DOMContentLoaded", () => {
  renderModules();
  renderApiQuick();
  renderApiRef();
  initTheme();
  initSearch();
  initNavHighlight();
});
