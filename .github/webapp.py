# 아이폰·PC용 웹앱(site/app.html) 만들기: 안드로이드 앱 화면 파일을 그대로 쓰고, 웹앱 표시만 덧붙임
# 사용: python3 .github/webapp.py <릴리스 번호>
import sys, re
n = (sys.argv[1] if len(sys.argv) > 1 else '').strip()
s = open('app/src/main/assets/index.html', encoding='utf-8').read()
head = ('<link rel="manifest" href="manifest.webmanifest">'
        '<link rel="apple-touch-icon" href="icon-180.png">'
        '<link rel="icon" href="icon-180.png">'
        '<meta name="apple-mobile-web-app-capable" content="yes">'
        '<meta name="mobile-web-app-capable" content="yes">'
        '<meta name="apple-mobile-web-app-title" content="챙김이">'
        '<meta name="apple-mobile-web-app-status-bar-style" content="default">')
assert '<title>챙김이</title>' in s
s = s.replace('<title>챙김이</title>', '<title>챙김이</title>' + head, 1)
# 웹앱 아이콘은 같은 주소(github.io)에서만 읽음
s = s.replace("img-src data: blob:;", "img-src 'self' data: blob:;", 1)
# 버전 표시: 웹앱은 열 때마다 최신이라, 빌드 번호(앱·다운로드 페이지와 같은 실제 번호)를 그대로 넣음
i = s.index("window.APP_INFO.version=function(){try{return window.Android.version()}")
j = s.index("}};", i) + 3
a = s[i:j]
assert a.count('function') >= 2 and len(a) < 400
assert n.isdigit(), '릴리스 번호가 필요해요'
s = s.replace(a, "window.APP_INFO.web=true;window.APP_INFO.version='1.%s';" % n, 1)
open('site/app.html', 'w', encoding='utf-8').write(s)
print('site/app.html', len(s.encode()), 'bytes, v1.' + n)
