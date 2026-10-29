@echo off
setlocal
echo == netease main
curl -s -o nul -w "music.163.com: %%{http_code}\n" --max-time 20 https://music.163.com/
echo == netease interface
curl -s -o nul -w "interface.music.163.com: %%{http_code}\n" --max-time 20 https://interface.music.163.com/
echo == netease interface3
curl -s -o nul -w "interface3.music.163.com: %%{http_code}\n" --max-time 20 https://interface3.music.163.com/
echo == netease search (public api)
curl -s --max-time 25 -o "%TEMP%\ne_search.json" -w "search: %%{http_code}\n" "https://music.163.com/api/search/get/web?s=test&type=1&limit=3" -H "User-Agent: Mozilla/5.0" -H "Referer: https://music.163.com/"
if exist "%TEMP%\ne_search.json" (
  echo --- response head ---
  powershell -NoProfile -Command "$c = Get-Content '%TEMP%\ne_search.json' -Raw; if ($c.Length -gt 300) { $c.Substring(0,300) } else { $c }"
)
echo == apple music api
curl -s -o nul -w "api.music.apple.com: %%{http_code}\n" --max-time 20 https://api.music.apple.com/v1/catalog/us/songs/1
endlocal
