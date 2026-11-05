/* Oh My CPA (Android) - the bridge between the console page and the native shell.
 * 1. reports the console's live theme tokens so the native panel, floating button and system
 *    bars wear the same palette (including a custom palette picked in OMC 设置 → 外观);
 * 2. optionally adds an "App 设置" entry to the console's own navigation that opens the native panel.
 * Everything is guarded: if the console's markup changes, the entry simply does not appear and
 * the floating button stays the way in. Nothing here touches the console's own state. */
(function () {
  if (window.__omcApp) { try { window.__omcApp.sync(); } catch (e) {} return; }
  var bridge = window.OmcApp;
  if (!bridge) return;
  var KEYS = ['--bg', '--surface', '--canvas', '--elevated', '--fg', '--fg-2', '--muted', '--meta', '--border',
    '--border-soft', '--accent', '--accent-on', '--hover', '--success', '--warn', '--danger'];
  var last = '';
  function report() {
    try {
      var root = document.documentElement, cs = getComputedStyle(root), o = {};
      o.mode = root.getAttribute('data-theme-mode') || (cs.colorScheme === 'light' ? 'light' : 'dark');
      for (var i = 0; i < KEYS.length; i++) o[KEYS[i]] = cs.getPropertyValue(KEYS[i]).trim();
      if (!o['--bg']) return;
      var s = JSON.stringify(o);
      if (s !== last) { last = s; bridge.theme(s); }
    } catch (e) {}
  }
  var ICON = '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="5" y="2" width="14" height="20" rx="2"/><path d="M12 18h.01"/></svg>';
  var CSS = '.omc-native-group{color:var(--meta);font-size:var(--text-sm,12px);font-weight:500;padding:16px 10px 4px}' +
    '.omc-native-entry{display:flex;align-items:center;gap:10px;height:32px;margin:1px 0;padding:0 16px 0 24px;border-radius:var(--radius-md,8px);' +
    'color:var(--fg-2);font-size:var(--text-base,14px);cursor:pointer;user-select:none;-webkit-tap-highlight-color:transparent;' +
    'transition:background-color var(--motion-fast,120ms) var(--ease-standard,ease),color var(--motion-fast,120ms) var(--ease-standard,ease)}' +
    '.omc-native-entry svg{flex:none;color:var(--muted)}' +
    '.omc-native-entry:hover,.omc-native-entry:active{color:var(--fg);background:color-mix(in srgb,var(--fg) 5%,transparent)}' +
    '.omc-native-entry:hover svg,.omc-native-entry:active svg{color:var(--fg)}';
  function ensureEntry() {
    if (!window.__omcInject) return;
    try {
      if (!document.getElementById('omc-native-css')) {
        var st = document.createElement('style');
        st.id = 'omc-native-css';
        st.textContent = CSS;
        (document.head || document.documentElement).appendChild(st);
      }
      var hosts = document.querySelectorAll('.app-sider-viewport');
      for (var i = 0; i < hosts.length; i++) {
        var host = hosts[i];
        if (!host.querySelector('.app-menu') || host.querySelector('.omc-native-box')) continue;
        if (host.closest('.ant-layout-sider-collapsed')) continue;
        var box = document.createElement('div');
        box.className = 'omc-native-box';
        box.innerHTML = '<div class="omc-native-group">App</div><div class="omc-native-entry" role="button" tabindex="0">' + ICON + '<span>App 设置</span></div>';
        box.querySelector('.omc-native-entry').addEventListener('click', function (e) {
          e.preventDefault(); e.stopPropagation();
          try {
            var mask = document.querySelector('.mobile-nav-drawer') && document.querySelector('.ant-drawer-mask');
            if (mask) mask.click();
          } catch (x) {}
          setTimeout(function () { try { bridge.openPanel(); } catch (x) {} }, 120);
        }, true);
        host.appendChild(box);
      }
    } catch (e) {}
  }
  var pending = false;
  function tick() {
    if (pending) return;
    pending = true;
    setTimeout(function () { pending = false; report(); ensureEntry(); }, 150);
  }
  try {
    new MutationObserver(tick).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme-mode', 'style', 'class'] });
    new MutationObserver(tick).observe(document.body || document.documentElement, { childList: true, subtree: true });
  } catch (e) {}
  window.__omcApp = { sync: function () { last = ''; tick(); } };
  tick();
})();
