# MotionCam

Minimalny camera-trap na Androida 10+ (API 29+). Nagrywa krótki film po wykryciu ruchu w kadrze i wrzuca go na udział SMB (np. dysk podłączony do Raspberry Pi przez Sambę).

## Jak to działa

1. **Foreground service** (`MotionService`, `foregroundServiceType="camera"`) trzyma kamerę aktywną w tle z `PARTIAL_WAKE_LOCK`.
2. **CameraX** binduje dwa use case'y do tej samej sesji:
   - `ImageAnalysis` (YUV_420_888, `KEEP_ONLY_LATEST`) → `MotionDetector` próbkuje płaszczyznę Y do siatki 32×24 i liczy komórki z deltą luminancji ≥ progu.
   - `VideoCapture` (HD, fallback do SD) startuje gdy detector odpali callback.
3. Po nagraniu plik trafia do `files/pending/` i jest kolejkowany do `UploadWorker` (WorkManager z exponential backoff i constraint `NetworkType.CONNECTED`).
4. **SMBJ** (`com.hierynomus:smbj`) wrzuca plik na share. Po sukcesie plik lokalny jest kasowany.
5. Po nagraniu jest cooldown (domyślnie 5 s) zanim detektor się ponownie uzbroi.

## Konfiguracja

Wszystko z poziomu jednej aktywności (`MainActivity`):

| Pole | Przykład |
| --- | --- |
| Host | `192.168.1.10` (IP Maliny) |
| Share | `media` (nazwa udziału w `/etc/samba/smb.conf`) |
| Podkatalog | `motioncam` (tworzony automatycznie) |
| User / Pass | dane usera SMB |
| Próg | 25 (delta luminancji 0–255) |
| Min. komórek | 40 z 768 — ile cel siatki musi się zmienić |
| Długość nagrania | 10 s |
| Cooldown | 5 s |

## Budowanie

W katalogu `motioncam/`:

```
./gradlew :app:assembleDebug
```

APK znajdziesz w `app/build/outputs/apk/debug/`.

Wymaga Android Studio Hedgehog+/Iguana lub JDK 17 + Android SDK 34.

## Uprawnienia

Aplikacja prosi w runtime o:

- `CAMERA`, `RECORD_AUDIO`
- `POST_NOTIFICATIONS` (Android 13+) — bez tego nie pokaże powiadomienia foreground service
- `FOREGROUND_SERVICE_CAMERA` (deklarowane w manifeście, Android 14+ wymaga go dla `type="camera"`)

## Strojenie wykrywania ruchu

- **Za dużo false-positiów** (wiatr, zmiany światła): podnieś próg (np. 35–45) i/lub min. komórek (np. 80–150).
- **Nie łapie powolnego ruchu**: obniż min. komórek (np. 20).
- **Tła z migoczącymi liśćmi**: rozważ wycięcie górnego/dolnego paska siatki — wymaga małej zmiany w `MotionDetector.sampleY`.

## Ograniczenia, o których warto wiedzieć

- **Bateria**: ciągle aktywna kamera + analiza klatek pożera ~15–25 %/h. Trzymaj telefon na ładowarce.
- **Doze / battery optimizations**: producenci (Xiaomi, Huawei) potrafią zabijać foreground services. Wyłącz optymalizacje baterii dla MotionCam w ustawieniach systemu.
- **SMB1**: SMBJ obsługuje tylko SMB2/3. Jeśli Samba na Malinie jest skonfigurowana wyłącznie pod SMB1, dodaj `server min protocol = SMB2` w `/etc/samba/smb.conf`.
- **Pre-buffer**: nagranie startuje ~200–400 ms po wykryciu (czas na `VideoCapture.prepareRecording().start`). Akceptowalne dla większości scenariuszy, ale szybki obiekt może opuścić kadr.
- **Combinations**: VideoCapture + ImageAnalysis bind nie działa na wszystkich starszych urządzeniach LEGACY; powinien działać na każdym Androidzie 10+ z hw level LIMITED+.

## Roadmap (gdyby się rozbudowywać)

- Pre-buffer (ring buffer surowych klatek → zapis 1–2 s przed triggerem)
- Maski obszarów (ignoruj wycinki kadru)
- Zdalny podgląd MJPEG po WiFi
- ML Kit Object Detection (filtruj „liście vs zwierzę")
