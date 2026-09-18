# ROADMAP

## Фаза 0 — Фундамент (текущая)
- [x] Клонировать базу (ByeByeDPI + сабмодули bye-dpi / hev-socks5-tunnel)
- [x] Изучить архитектуру, найти точки интеграции фиксов
- [x] PRD.md
- [ ] CI: GitHub Actions — сборка APK (universal + ABI-splits) на каждый push/tag

## Фаза 1 — Надежность (главные фиксы)
- [ ] F1: `--quic-block` в C-ядре (params.h / main.c / proxy.c) + настройка
      `byedpi_quic_block` (default ON) + прокидывание в uiargs
- [ ] F2: `LocalDnsServer` — локальный DNS на TUN-адресе, DoH через
      SSLSocket (SNI + endpoint identification), провайдеры:
      dns.google (8.8.8.8, 8.8.4.4), unfiltered.adguard-dns.com (94.140.14.140);
      UDP + TCP listeners, кэш, интеграция в ByeDpiVpnService, настройка
      `byedpi_doh` (default ON)
- [ ] F3: AutotuneEngine — автоподбор стратегии (переиспользовать механику
      TestActivity/SiteCheckUtils: прогон матрицы стратегий через SOCKS5,
      выбор рабочей) + RemotePresets (JSON на GitHub raw, пресеты по ASN,
      probe-URL'ы, версия конфига)
- [ ] F4: Watchdog — цикл в ByeDpiVpnService (проба каждые ~20 c через
      SOCKS5, метрика: латенти + скорость; 2 подряд провала → смена
      стратегии → рестарт ядра → нотификация)

## Фаза 2 — UX и ТВ
- [ ] Онбординг «одна кнопка»: первый запуск = сразу autotune + старт,
      без ручных настроек
- [ ] TV-полировка: D-pad фокусы, крупные элементы, banner
- [ ] Автозапуск при загрузке (есть в базе — проверить на реальном ТВ)
- [ ] Самообновление APK (GitHub Releases API, REQUEST_INSTALL_PACKAGES)
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
