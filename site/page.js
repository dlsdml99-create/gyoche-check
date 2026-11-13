// 다운로드 페이지 QR 그리기 (CSP: 같은 사이트 스크립트만 허용, 인라인 스크립트 없음)
(function () {
  var el = document.getElementById('qr-cg');
  if (!el) return;
  var url = el.getAttribute('data-url') || '';
  if (typeof qrcode !== 'function' || !/^https:\/\/github\.com\/dlsdml99-create\/gyoche-check\//.test(url)) { el.parentNode.hidden = true; return; }
  try {
    var q = qrcode(0, 'M'); q.addData(url); q.make();
    var n = q.getModuleCount(), d = '';
    for (var r = 0; r < n; r++) for (var c = 0; c < n; c++) if (q.isDark(r, c)) d += 'M' + c + ' ' + r + 'h1v1h-1z';
    el.innerHTML = '<svg viewBox="0 0 ' + n + ' ' + n + '" shape-rendering="crispEdges" role="img" aria-label="다운로드 QR"><path fill="#111" d="' + d + '"/></svg>';
  } catch (e) { el.parentNode.hidden = true; }
})();
