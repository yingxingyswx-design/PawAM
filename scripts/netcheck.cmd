@echo off
setlocal
echo == direct google
curl -s -o nul -w "google direct: %%{http_code}\n" --max-time 15 https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/8.13.0/gradle-8.13.0.pom
echo == proxy google
curl -s -o nul -w "google proxy: %%{http_code}\n" --max-time 25 -x http://127.0.0.1:9876 https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/8.13.0/gradle-8.13.0.pom
echo == direct maven central
curl -s -o nul -w "central direct: %%{http_code}\n" --max-time 15 https://repo.maven.apache.org/maven2/
echo == proxy maven central
curl -s -o nul -w "central proxy: %%{http_code}\n" --max-time 25 -x http://127.0.0.1:9876 https://repo.maven.apache.org/maven2/
echo == proxy gradle dist
curl -s -o nul -w "gradle proxy: %%{http_code}\n" -I --max-time 25 -x http://127.0.0.1:9876 https://services.gradle.org/distributions/gradle-9.3.1-bin.zip
echo == proxy jitpack
curl -s -o nul -w "jitpack proxy: %%{http_code}\n" --max-time 25 -x http://127.0.0.1:9876 https://jitpack.io/
echo == proxy plugin portal
curl -s -o nul -w "portal proxy: %%{http_code}\n" --max-time 25 -x http://127.0.0.1:9876 https://plugins.gradle.org/m2/
echo == proxy lineage m2
curl -s -o nul -w "lineage proxy: %%{http_code}\n" --max-time 25 -x http://127.0.0.1:9876 https://raw.githubusercontent.com/lineage-next/gradle-generatebp/v1.31/.m2/
endlocal
