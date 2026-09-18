# ROADMAP

## Фаза 0 — Фундамент (текущая)
- [x] Клонировать базу (ByeByeDPI + сабмодули bye-dpi / hev-socks5-tunnel)
- [x] Изучить архитектуру, найти точки интеграции фиксов
- [x] PRD.md
- [ ] CI: GitHub Actions — сборка APK (universal + ABI-splits) на каждый push/tag

## Фаза 1 — Надежность (главные фиксы)
- [x] F1: `--quic-block` в C-ядре (params.h / main.c / proxy.c) + настройка
      `byedpi_quic_block` (default ON) + прокидывание в uiargs
- [x] F2: `LocalDnsServer` — локальный DNS на TUN-адресе, DoH через
      SSLSocket (SNI + endpoint identification), провайдеры:
      dns.google (8.8.8.8, 8.8.4.4), unfiltered.adguard-dns.com (94.140.14.140);
      UDP + TCP listeners, кэш, интеграция в ByeDpiVpnService, настройка
      `byedpi_doh` (default ON)
- [x] F3: StrategyAutotune — автоподбор стратегии (headless-прогон кандидатов
      через прокси, выбор лучшей, ранний выход на идеальном счёте) +
      RemotePresets (presets.json на GitHub raw + jsDelivr, кэш 12 ч,
      probe-сайты и стратегии без релиза приложения) + автозапуск
      подбора при первом подключении
- [x] F4: Watchdog — в ByeDpiVpnService (проба каждые 20 c через SOCKS5,
      3 подряд провала → автотюн (app-scope, переживает остановку сервиса),
      кулдаун 10 мин)

## Фаза 2 — UX и ТВ
- [ ] Онбординг «одна кнопка»: первый запуск = сразу autotune + старт,
      без ручных настроек
- [x] TV-полировка: D-pad фокусы, крупные элементы, banner (унаследовано
      от базы + проверено на ТВ)
- [x] Автозапуск при загрузке (есть в базе)
- [x] Самообновление APK (UpdaterUtils: GitHub Releases API → скачивание
      universal APK → нотификация с one-tap установкой; FileProvider +
      REQUEST_INSTALL_PACKAGES)
- [ ] Rebrand: имя, иконка, splash (решить нейминг)

## Фаза 3 — Дистрибуция
- [ ] GitHub Releases + release-notes на русском
- [ ] Obtainium/IzzyOnDroid листинг
- [ ] Лендинг (GitHub Pages) с QR-кодами
- [ ] Telegram-канал

## Фаза 4 — Гибрид (опционально, после валидации спроса)
- [ ] VPN-фолбэк: sing-box/Xray клиент + свои VPS, платная подписка
- [ ] Биллинг: оферта + Prodamus/ЮKassa
- [ ] Автопереключение DPI → VPN при системном отказе DPI на операторе

## Известные риски
- ТСПУ закрывает конкретные трюки → F3/F4 сокращают простой, но не устраняют
- Полная IP-блокировка YouTube убьет DPI-путь целиком → тогда Фаза 4
- Google Play может удалить приложение за «обход» → каналы: APK/Obtainium/IzzyOnDroid
