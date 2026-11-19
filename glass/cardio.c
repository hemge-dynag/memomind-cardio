#include "gm_plugin_lvgl_api.h"
#include "gm_plugin_libc.h"
#include "../game/common/firmware_exit.h"

/* Paired with the Android client MemoMind Cardio (repos/memomind-cardio-android).
 *
 * The phone owns the BPM sensor (BLE heart-rate strap / smartwatch) and streams
 * one reading at a time to this plugin. The glasses only render: a large BPM
 * value, the heart-rate zone it belongs to, and, while the phone tracks the
 * workout, one line with elapsed time, speed and distance.
 *
 * Wire protocol, version 1 (identical to PhoneSDK/examples/cardio/protocol.js):
 *   downlink channel 0x4344 ('CD'), phone -> glasses
 *     [ver, 1 HEART_RATE, bpm_lo, bpm_hi, zone, flags, trend]
 *     [ver, 2 STATUS, state]
 *     [ver, 3 CONFIG, resting, maximal]
 *     [ver, 4 TRACKING, flags, time_s (4 LE), speed_0.1kmh (2 LE),
 *                              distance_m (4 LE)]
 *   uplink channel 0x4355 ('CU'), glasses -> phone
 *     [ver, EVENT_READY 1]           sent on start and on every reconnection
 *
 * flags bit 0 = bad skin contact. The zone byte the phone computes is advisory:
 * this plugin recomputes it from CONFIG (Karvonen) so the display stays right
 * even if a reading arrives before the config.
 *
 * The TRACKING flags bit 0 means "tracking active": the metrics line only
 * shows while it is set. Flags bit 1 marks a speed-less sport (generic
 * workout): only the elapsed time is drawn, no speed or distance.
 *
 * Button: long press (2 s) quits through the native firmware exit ring. A
 * single press toggles the display off and on. */

#define CARDIO_CHANNEL UINT16_C(0x4344) /* 'CD': phone -> glasses */
#define CARDIO_EVENT_CHANNEL UINT16_C(0x4355) /* 'CU': glasses -> phone */
#define PROTOCOL_VERSION 1U

#define COMMAND_HEART_RATE 1U
#define COMMAND_STATUS 2U
#define COMMAND_CONFIG 3U
#define COMMAND_TRACKING 4U

#define EVENT_READY UINT8_C(1)
#define READY_REANNOUNCE_MS UINT32_C(1500)

#define FLAG_CONTACT_BAD UINT8_C(0x01)
#define TRACKING_ACTIVE UINT8_C(0x01)
#define TRACKING_NO_SPEED UINT8_C(0x02)

#define STATE_IDLE 0U
#define STATE_CONNECTING 1U
#define STATE_STREAMING 2U
#define STATE_LOST 3U

#define ZONE_NONE 0U
#define ZONE_REST 1U
#define ZONE_MODERATE 2U
#define ZONE_EFFORT 3U
#define ZONE_MAXIMUM 4U

#define DEFAULT_RESTING 60U
#define DEFAULT_MAXIMUM 190U

#define BPM_STALE_MS 12000U
#define REFRESH_INTERVAL_MS 250U
#define MIN_INTERVAL_MS 20U

#define SCREEN_MARGIN 20
#define TITLE_HEIGHT 22
#define BPM_HEIGHT 150
#define UNIT_HEIGHT 22
#define ZONE_HEIGHT 34
#define METRICS_HEIGHT 30

typedef struct {
    const gm_plugin_host_api_t *host;
    const gm_plugin_lvgl_api_t *ui;
    const gm_plugin_libc_extension_api_t *libc;

    gm_plugin_lvgl_obj_t *screen;
    gm_plugin_lvgl_obj_t *title_label;
    gm_plugin_lvgl_obj_t *bpm_label;
    gm_plugin_lvgl_obj_t *unit_label;
    gm_plugin_lvgl_obj_t *zone_label;
    gm_plugin_lvgl_obj_t *metrics_label;

    bool ui_ready;
    bool have_reading;
    bool contact_bad;
    bool stale;
    uint8_t state;

    uint16_t bpm;
    uint8_t zone;
    uint8_t resting;
    uint8_t maximal;

    /* Workout metrics pushed by the phone while it tracks the run. */
    bool tracking;
    bool tracking_no_speed;
    uint32_t tracking_sec;
    uint16_t speed_dkmh;
    uint32_t distance_m;

    uint32_t interval_ms; /* 0 or < MIN => sample the last reading */
    uint32_t stale_elapsed_ms;

    /* Re-announce EVENT_READY until the phone answers with a downlink. The
     * first announcement at start usually races the phone enabling the
     * uplink notifications on 0x2022, so it is dropped; without a retry the
     * phone never learns we are here and falls back to the Web Bridge. */
    bool phone_heard;      /* phone sent a downlink: it saw EVENT_READY */
    uint32_t ready_elapsed_ms;

    firmware_exit_t exit_ui;

    uint8_t language; /* 0=fr 1=en 2=es 3=it 4=de 5=zh-CN */
} cardio_t;

/* Picks the string for the detected UI locale. */
#define L(fr_str, en_str, es_str, it_str, de_str, zh_str) \
    (ca.language == 1U ? (en_str) : ca.language == 2U ? (es_str) : \
     ca.language == 3U ? (it_str) : ca.language == 4U ? (de_str) : \
     ca.language == 5U ? (zh_str) : (fr_str))

static cardio_t ca;

static uint8_t cardio_detect_language(const char *locale)
{
    if (locale[0] == 'e' && locale[1] == 'n') return 1U;
    if (locale[0] == 'e' && locale[1] == 's') return 2U;
    if (locale[0] == 'i' && locale[1] == 't') return 3U;
    if (locale[0] == 'd' && locale[1] == 'e') return 4U;
    if (locale[0] == 'z' && locale[1] == 'h') return 5U;
    return 0U;
}

#define number gm_plugin_lvgl_style_number
#define color gm_plugin_lvgl_style_color

static void set_style(gm_plugin_lvgl_obj_t *object,
                      gm_plugin_lvgl_style_prop_t property,
                      gm_plugin_lvgl_style_value_t value)
{
    ca.ui->style_set(object, property, value, GM_PLUGIN_LVGL_SELECTOR_MAIN);
}

static void set_font(gm_plugin_lvgl_obj_t *object,
                     const gm_plugin_lvgl_font_t *font)
{
    gm_plugin_lvgl_style_value_t value = {0};
    value.ptr = font;
    set_style(object, GM_PLUGIN_LVGL_STYLE_TEXT_FONT, value);
}

static void style_panel(gm_plugin_lvgl_obj_t *object, uint8_t fill,
                        uint8_t border, uint8_t radius)
{
    set_style(object, GM_PLUGIN_LVGL_STYLE_BG_COLOR, color(fill));
    set_style(object, GM_PLUGIN_LVGL_STYLE_BG_OPA, number(255));
    set_style(object, GM_PLUGIN_LVGL_STYLE_BORDER_COLOR, color(0xA0));
    set_style(object, GM_PLUGIN_LVGL_STYLE_BORDER_OPA, number(255));
    set_style(object, GM_PLUGIN_LVGL_STYLE_BORDER_WIDTH, number(border));
    set_style(object, GM_PLUGIN_LVGL_STYLE_RADIUS, number(radius));
    ca.ui->obj_clear_flag(object, GM_PLUGIN_LVGL_FLAG_SCROLLABLE);
}

static gm_plugin_lvgl_obj_t *make_label(gm_plugin_lvgl_obj_t *parent,
                                        const char *text, uint8_t shade)
{
    gm_plugin_lvgl_obj_t *label = ca.ui->label_create(parent);
    if (label == 0) return 0;
    ca.ui->label_set_text(label, text);
    ca.ui->label_set_long_mode(label, GM_PLUGIN_LVGL_LABEL_DOT);
    set_style(label, GM_PLUGIN_LVGL_STYLE_TEXT_COLOR, color(shade));
    set_style(label, GM_PLUGIN_LVGL_STYLE_TEXT_ALIGN,
              number(GM_PLUGIN_LVGL_TEXT_ALIGN_CENTER));
    return label;
}

/* ---- Zone model (must match GmFrame.cardioZone in the Android client) ---- */

static uint8_t compute_zone(uint16_t bpm)
{
    int32_t reserve = (int32_t)ca.maximal - (int32_t)ca.resting;
    int32_t low;
    if (reserve < 20) return ZONE_NONE;
    low = (int32_t)ca.resting + 5;
    if ((int32_t)bpm <= low) return ZONE_REST;
    if ((int32_t)bpm < (int32_t)ca.resting + reserve * 50 / 100) return ZONE_MODERATE;
    if ((int32_t)bpm < (int32_t)ca.resting + reserve * 70 / 100) return ZONE_EFFORT;
    return ZONE_MAXIMUM;
}

static const char *zone_name(void)
{
    switch (ca.zone) {
    case ZONE_REST:
        return L("Repos", "Rest", "Reposo", "Riposo", "Ruhe", "静息");
    case ZONE_MODERATE:
        return L("Modere", "Moderate", "Moderado", "Moderato", "Moderat", "适度");
    case ZONE_EFFORT:
        return L("Effort", "Effort", "Esfuerzo", "Sforzo", "Belastung", "用力");
    case ZONE_MAXIMUM:
        return L("Maximum", "Maximum", "Máximo", "Massimo", "Maximum", "极限");
    default:
        return L("Hors zone", "Out of zone", "Fuera de zona", "Fuori zona", "Außerhalb", "不在区间");
    }
}

/* Brightness shade per zone, so the big number reads at a glance even in
 * monochrome. */
static uint8_t zone_shade(void)
{
    switch (ca.zone) {
    case ZONE_REST: return 0xA8;
    case ZONE_MODERATE: return 0xC8;
    case ZONE_EFFORT: return 0xE8;
    case ZONE_MAXIMUM: return 0xFF;
    default: return 0x90;
    }
}

/* ---- Workout metrics ---- */

/* One line, three glanceable fields: time, speed, distance. A speed-less
 * sport only gets the elapsed time. */
static void metrics_text(char *buffer, uint32_t size)
{
    uint32_t minutes = ca.tracking_sec / 60U;
    uint32_t seconds = ca.tracking_sec % 60U;
    if (ca.tracking_no_speed) {
        ca.libc->snprintf(buffer, size, "%u:%02u",
                          (unsigned int)minutes, (unsigned int)seconds);
        return;
    }
    uint32_t km = ca.distance_m / 1000U;
    uint32_t hundredths = (ca.distance_m % 1000U) / 10U;
    if (ca.distance_m >= 1000U)
        ca.libc->snprintf(buffer, size, "%u:%02u  %u.%u  %u.%02u km",
                          (unsigned int)minutes, (unsigned int)seconds,
                          (unsigned int)(ca.speed_dkmh / 10U),
                          (unsigned int)(ca.speed_dkmh % 10U),
                          (unsigned int)km, (unsigned int)hundredths);
    else
        ca.libc->snprintf(buffer, size, "%u:%02u  %u.%u km/h  %u m",
                          (unsigned int)minutes, (unsigned int)seconds,
                          (unsigned int)(ca.speed_dkmh / 10U),
                          (unsigned int)(ca.speed_dkmh % 10U),
                          (unsigned int)ca.distance_m);
}

/* ---- Display ---- */

static void request_screen_on(void)
{
    (void)ca.host->display_control.screen_turn_on(true);
}

static void refresh_display(void)
{
    char text[48];

    if (!ca.ui_ready) return;

    if (!ca.have_reading) {
        ca.ui->label_set_text(ca.bpm_label, "--");
        set_style(ca.bpm_label, GM_PLUGIN_LVGL_STYLE_TEXT_COLOR, color(0x60));
        ca.ui->label_set_text(ca.unit_label, "BPM");
        ca.ui->label_set_text(ca.zone_label,
            ca.state == STATE_CONNECTING
                ? L("Connexion au capteur", "Connecting sensor", "Conectando sensor", "Connessione sensore", "Sensor verbinden", "连接传感器")
                : L("En attente du capteur", "Waiting for the sensor", "Esperando el sensor", "In attesa del sensore", "Warte auf Sensor", "等待传感器"));
        set_style(ca.zone_label, GM_PLUGIN_LVGL_STYLE_TEXT_COLOR, color(0x80));
        ca.ui->label_set_text(ca.metrics_label, "");
        ca.ui->obj_add_flag(ca.metrics_label, GM_PLUGIN_LVGL_FLAG_HIDDEN);
        return;
    }

    ca.libc->snprintf(text, sizeof(text), "%u", (unsigned int)ca.bpm);
    ca.ui->label_set_text(ca.bpm_label, text);
    set_style(ca.bpm_label, GM_PLUGIN_LVGL_STYLE_TEXT_COLOR,
              color(ca.stale ? 0x70 : zone_shade()));
    ca.ui->label_set_text(ca.unit_label, "BPM");

    if (ca.contact_bad) {
        ca.ui->label_set_text(ca.zone_label,
            L("Contact capteur a verifier", "Check the sensor contact", "Revisa el contacto del sensor", "Controlla il contatto del sensore", "Sensorkontakt prüfen", "请检查传感器接触"));
        set_style(ca.zone_label, GM_PLUGIN_LVGL_STYLE_TEXT_COLOR, color(0xD0));
    } else if (ca.stale || ca.state == STATE_LOST) {
        ca.ui->label_set_text(ca.zone_label,
            L("Signal perdu", "Signal lost", "Señal perdida", "Segnale perso", "Signal verloren", "信号丢失"));
        set_style(ca.zone_label, GM_PLUGIN_LVGL_STYLE_TEXT_COLOR, color(0x80));
    } else {
        ca.ui->label_set_text(ca.zone_label, zone_name());
        set_style(ca.zone_label, GM_PLUGIN_LVGL_STYLE_TEXT_COLOR,
                  color(zone_shade()));
    }

    if (ca.tracking) {
        metrics_text(text, sizeof(text));
        ca.ui->label_set_text(ca.metrics_label, text);
        set_style(ca.metrics_label, GM_PLUGIN_LVGL_STYLE_TEXT_COLOR, color(0xE0));
        ca.ui->obj_clear_flag(ca.metrics_label, GM_PLUGIN_LVGL_FLAG_HIDDEN);
    } else {
        ca.ui->label_set_text(ca.metrics_label, "");
        ca.ui->obj_add_flag(ca.metrics_label, GM_PLUGIN_LVGL_FLAG_HIDDEN);
    }
}

/* ---- Uplink ---- */

static void send_ready_event(void)
{
    uint8_t payload[2];
    payload[0] = PROTOCOL_VERSION;
    payload[1] = EVENT_READY;
    (void)ca.host->bt_send(CARDIO_EVENT_CHANNEL, payload, sizeof(payload));
}

/* ---- Downlink ---- */

static void apply_heart_rate(const uint8_t *data, uint32_t length)
{
    uint16_t bpm;
    if (length < 5U) return;
    bpm = (uint16_t)((uint16_t)data[2] | ((uint16_t)data[3] << 8));
    ca.contact_bad = length >= 6U && (data[5] & FLAG_CONTACT_BAD) != 0U;

    if (bpm == 0U) {
        /* A zero reading means the strap dropped its signal. */
        ca.have_reading = false;
        ca.state = STATE_LOST;
        refresh_display();
        return;
    }

    ca.bpm = bpm;
    ca.zone = compute_zone(bpm);
    ca.have_reading = true;
    ca.stale = false;
    ca.stale_elapsed_ms = 0U;
    ca.state = STATE_STREAMING;
    refresh_display();
}

static void apply_status(const uint8_t *data, uint32_t length)
{
    if (length < 3U) return;
    ca.state = data[2];
    if (ca.state == STATE_LOST) ca.stale = true;
    if (ca.state == STATE_STREAMING) ca.stale_elapsed_ms = 0U;
    refresh_display();
}

static void apply_config(const uint8_t *data, uint32_t length)
{
    if (length < 4U) return;
    ca.resting = data[2] < 30U ? 30U : data[2];
    ca.maximal = data[3] < 100U ? 100U : data[3];
    if (ca.maximal <= ca.resting + 10U) ca.maximal = (uint8_t)(ca.resting + 60U);
    if (ca.have_reading) ca.zone = compute_zone(ca.bpm);
    refresh_display();
}

/* [ver, 4 TRACKING, flags, time_s (4 LE), speed_0.1kmh (2 LE), distance_m
 * (4 LE)]. Flags bit 0 = tracking active. */
static void apply_tracking(const uint8_t *data, uint32_t length)
{
    int was;
    char text[48];
    if (length < 13U) return;
    was = ca.tracking;
    ca.tracking = (data[2] & TRACKING_ACTIVE) != 0U;
    ca.tracking_no_speed = (data[2] & TRACKING_NO_SPEED) != 0U;
    ca.tracking_sec = (uint32_t)data[3] | ((uint32_t)data[4] << 8) |
                      ((uint32_t)data[5] << 16) | ((uint32_t)data[6] << 24);
    ca.speed_dkmh = (uint16_t)((uint16_t)data[7] | ((uint16_t)data[8] << 8));
    ca.distance_m = (uint32_t)data[9] | ((uint32_t)data[10] << 8) |
                    ((uint32_t)data[11] << 16) | ((uint32_t)data[12] << 24);
    if (!ca.ui_ready || !ca.tracking || was != ca.tracking) {
        refresh_display();
        return;
    }
    metrics_text(text, sizeof(text));
    ca.ui->label_set_text(ca.metrics_label, text);
}

static void handle_cardio_message(const uint8_t *data, uint32_t length)
{
    if (data == 0 || length < 2U) return;
    if (data[0] != PROTOCOL_VERSION) return;

    /* Any valid downlink proves the phone received our EVENT_READY and is
     * talking to us, so we can stop re-announcing. */
    ca.phone_heard = true;

    switch (data[1]) {
    case COMMAND_HEART_RATE:
        apply_heart_rate(data, length);
        break;
    case COMMAND_STATUS:
        apply_status(data, length);
        break;
    case COMMAND_CONFIG:
        apply_config(data, length);
        break;
    case COMMAND_TRACKING:
        apply_tracking(data, length);
        break;
    default:
        break;
    }
}

/* ---- Lifecycle ---- */

static gm_plugin_result_t cardio_start(void *context)
{
    gm_plugin_display_info_t display;
    gm_plugin_lvgl_obj_t *root;
    char locale[GM_PLUGIN_LOCALE_TAG_MAX];
    int32_t panel_w;
    gm_plugin_lvgl_coord_t bpm_line;
    (void)context;

    ca.language = 0U;
    ca.ui_ready = false;
    if (ca.host->locale_get != 0 &&
        ca.host->locale_get(locale) == GM_PLUGIN_OK)
        ca.language = cardio_detect_language(locale);

    if (ca.host->display_get_info(&display) != GM_PLUGIN_OK ||
        display.width <= SCREEN_MARGIN * 2U ||
        display.height < TITLE_HEIGHT + BPM_HEIGHT + UNIT_HEIGHT +
                           ZONE_HEIGHT + METRICS_HEIGHT + 50)
        return GM_PLUGIN_ESTATE;

    root = ca.ui->root_get();
    if (root == 0) return GM_PLUGIN_ESTATE;
    ca.ui->obj_clean(root);

    ca.screen = ca.ui->obj_create(root);
    if (ca.screen == 0) return GM_PLUGIN_ENOMEM;
    ca.ui->obj_set_size(ca.screen, display.width, display.height);
    ca.ui->obj_align(ca.screen, GM_PLUGIN_LVGL_ALIGN_CENTER, 0, 0);
    style_panel(ca.screen, 0x00, 0, 0);
    panel_w = display.width - SCREEN_MARGIN * 2;

    bpm_line = ca.ui->font_get_line_height != 0
        ? ca.ui->font_get_line_height(ca.ui->font_large)
        : (gm_plugin_lvgl_coord_t)BPM_HEIGHT;

    ca.title_label = make_label(ca.screen, "Cardio", 0x70);
    if (ca.title_label == 0) goto no_memory;
    ca.ui->obj_set_size(ca.title_label, panel_w, TITLE_HEIGHT);
    ca.ui->obj_align(ca.title_label, GM_PLUGIN_LVGL_ALIGN_TOP_MID, 0, 8);

    /* The BPM number is the whole point of the screen: it gets the large font
     * and the centre, with the unit and the zone stacked right below. */
    ca.bpm_label = make_label(ca.screen, "--", 0xF0);
    if (ca.bpm_label == 0) goto no_memory;
    set_font(ca.bpm_label, ca.ui->font_large);
    ca.ui->obj_set_size(ca.bpm_label, panel_w, BPM_HEIGHT);
    ca.ui->obj_align(ca.bpm_label, GM_PLUGIN_LVGL_ALIGN_CENTER, 0, -28);

    ca.unit_label = make_label(ca.screen, "BPM", 0x60);
    if (ca.unit_label == 0) goto no_memory;
    ca.ui->obj_set_size(ca.unit_label, panel_w, UNIT_HEIGHT);
    ca.ui->obj_align(ca.unit_label, GM_PLUGIN_LVGL_ALIGN_CENTER, 0,
                     (gm_plugin_lvgl_coord_t)(bpm_line / 2 + 8));

    ca.zone_label = make_label(ca.screen, "", 0xC0);
    if (ca.zone_label == 0) goto no_memory;
    ca.ui->obj_set_size(ca.zone_label, panel_w, ZONE_HEIGHT);
    ca.ui->obj_align(ca.zone_label, GM_PLUGIN_LVGL_ALIGN_CENTER, 0,
                     (gm_plugin_lvgl_coord_t)(bpm_line / 2 + 8 + UNIT_HEIGHT + 4));

    ca.metrics_label = make_label(ca.screen, "", 0xE0);
    if (ca.metrics_label == 0) goto no_memory;
    ca.ui->obj_set_size(ca.metrics_label, panel_w, METRICS_HEIGHT);
    ca.ui->obj_align(ca.metrics_label, GM_PLUGIN_LVGL_ALIGN_BOTTOM_MID, 0, -34);
    ca.ui->obj_add_flag(ca.metrics_label, GM_PLUGIN_LVGL_FLAG_HIDDEN);

    {
        gm_plugin_result_t exit_result = firmware_exit_create(
            &ca.exit_ui, ca.ui, ca.screen, display.width, display.height,
            L("Maintenez 2 s pour quitter",
              "Hold for 2 s to exit",
              "Mantén pulsado 2 s para salir",
              "Tieni premuto 2 s per uscire",
              "2 s gedrückt halten, um zu beenden",
              "按住2s退出"));
        if (exit_result != GM_PLUGIN_OK) goto no_memory;
    }

    ca.resting = DEFAULT_RESTING;
    ca.maximal = DEFAULT_MAXIMUM;
    ca.ui_ready = true;
    ca.phone_heard = false;
    ca.ready_elapsed_ms = 0U;

    refresh_display();
    send_ready_event();
    return GM_PLUGIN_OK;

no_memory:
    ca.ui->obj_clean(root);
    ca.screen = 0;
    ca.title_label = 0;
    ca.bpm_label = 0;
    ca.unit_label = 0;
    ca.zone_label = 0;
    ca.metrics_label = 0;
    ca.exit_ui.overlay = 0;
    ca.exit_ui.ring = 0;
    ca.exit_ui.ring_full = 0;
    ca.exit_ui.active = false;
    ca.exit_ui.committed = false;
    return GM_PLUGIN_ENOMEM;
}

/* Request the native Host exit. app_exit() is asynchronous: no UI work and no
 * second request may follow it. */
static void request_exit(void)
{
    ca.exit_ui.active = false;
    ca.exit_ui.committed = true;
    ca.host->app_exit();
}

static void cardio_loop(void *context, uint32_t elapsed_ms)
{
    (void)context;

    /* Keep the opaque exit overlay up; the Host unloads us asynchronously. */
    if (ca.exit_ui.committed) return;

    if (ca.exit_ui.active) {
        if (firmware_exit_complete(&ca.exit_ui, ca.ui, ca.host->monotonic_ms()))
            request_exit();
        return;
    }

    if (elapsed_ms > 500U) elapsed_ms = 500U;

    /* Re-announce ourselves until the phone answers. The start-time send is
     * usually lost because the phone enables the uplink notifications a
     * moment later; a slow retry closes that race. */
    if (!ca.phone_heard) {
        ca.ready_elapsed_ms += elapsed_ms;
        if (ca.ready_elapsed_ms >= READY_REANNOUNCE_MS) {
            ca.ready_elapsed_ms = 0U;
            send_ready_event();
        }
    }

    /* Mark a reading stale when the phone stops streaming: the number stays
     * on screen but dimmed, so the wearer never trusts an old value. */
    if (ca.have_reading && !ca.stale) {
        ca.stale_elapsed_ms += elapsed_ms;
        if (ca.stale_elapsed_ms >= BPM_STALE_MS) {
            ca.stale = true;
            refresh_display();
        }
    }

    /* Throttled redraw: the phone pushes at most a few readings per second,
     * but the trend line and the stale timer still need a periodic tick. */
    if (ca.interval_ms < REFRESH_INTERVAL_MS) {
        ca.interval_ms += elapsed_ms;
        if (ca.interval_ms < REFRESH_INTERVAL_MS) return;
    }
    ca.interval_ms = 0U;
}

static bool cardio_event(void *context, const gm_plugin_event_t *event)
{
    (void)context;
    if (event == 0) return false;

    if (event->type == GM_PLUGIN_EVENT_BT_MESSAGE) {
        if (event->data.bt.channel != CARDIO_CHANNEL || event->data.bt.data == 0)
            return false;
        handle_cardio_message(event->data.bt.data, event->data.bt.length);
        return true;
    }

    if (event->type == GM_PLUGIN_EVENT_BUTTON &&
        event->data.button.button == GM_PLUGIN_BUTTON_PRIMARY) {
        gm_plugin_button_action_t action = event->data.button.action;

        if (ca.exit_ui.committed) return true;

        if (action == GM_PLUGIN_BUTTON_ACTION_LONG ||
            action == GM_PLUGIN_BUTTON_ACTION_VERY_LONG) {
            if (ca.exit_ui.overlay == 0) {
                ca.host->app_exit();
                return true;
            }
            request_screen_on();
            firmware_exit_begin(&ca.exit_ui, ca.ui, ca.host->monotonic_ms());
            return true;
        }
        if (action == GM_PLUGIN_BUTTON_ACTION_RELEASE) {
            if (ca.exit_ui.active) firmware_exit_cancel(&ca.exit_ui, ca.ui);
            return true;
        }
        if (action == GM_PLUGIN_BUTTON_ACTION_SINGLE) {
            /* One press blanks the display, the next brings it back. Wake
             * first when it was off so the wearer always sees the HUD. */
            bool on = ca.host->display_control.screen_is_on();
            (void)ca.host->display_control.screen_turn_on(!on);
            if (!on) refresh_display();
            return true;
        }
        if (action == GM_PLUGIN_BUTTON_ACTION_DOUBLE) {
            request_screen_on();
            refresh_display();
            return true;
        }
    }

    if (event->type == GM_PLUGIN_EVENT_CONNECTION) {
        if (event->data.connection.connected) {
            /* The phone came back (app restart, link drop): re-announce
             * ourselves so it replays its configuration and the current
             * reading. */
            ca.phone_heard = false;
            ca.ready_elapsed_ms = 0U;
            send_ready_event();
        } else {
            /* Link lost: the next phone will need a fresh announcement. */
            ca.phone_heard = false;
        }
        return true;
    }

    return false;
}

static void cardio_stop(void *context)
{
    gm_plugin_lvgl_obj_t *root;
    (void)context;
    ca.ui_ready = false;
    root = ca.ui->root_get();
    if (root != 0) ca.ui->obj_clean(root);
    ca.screen = 0;
    ca.title_label = 0;
    ca.bpm_label = 0;
    ca.unit_label = 0;
    ca.zone_label = 0;
    ca.metrics_label = 0;
    ca.exit_ui.overlay = 0;
    ca.exit_ui.ring = 0;
    ca.exit_ui.ring_full = 0;
    ca.exit_ui.active = false;
    ca.exit_ui.committed = false;
}

gm_plugin_result_t gm_plugin_entry(const gm_plugin_host_api_t *host,
                                   gm_plugin_descriptor_t *plugin)
{
    const gm_plugin_capabilities_t required =
        GM_PLUGIN_CAP_BLUETOOTH | GM_PLUGIN_CAP_BUTTON |
        GM_PLUGIN_CAP_DISPLAY_CONTROL | GM_PLUGIN_CAP_LOCALE;

    if (host == 0 || plugin == 0 || host->log == 0 || host->bt_send == 0 ||
        host->display_get_info == 0 || host->graphics.lvgl == 0 ||
        host->display_control.screen_is_on == 0 ||
        host->display_control.screen_turn_on == 0 ||
        host->app_exit == 0 ||
        !GM_PLUGIN_VERSION_COMPATIBLE(host->abi_version,
                                      GM_PLUGIN_ABI_MIN_VERSION) ||
        host->struct_size < GM_PLUGIN_HOST_API_MIN_SIZE ||
        plugin->struct_size < GM_PLUGIN_DESCRIPTOR_MIN_SIZE ||
        (host->capabilities & required) != required)
        return GM_PLUGIN_ENOTSUP;

    ca.host = host;
    ca.ui = host->graphics.lvgl;
    if (gm_plugin_libc_get(host, &ca.libc) != GM_PLUGIN_OK)
        return GM_PLUGIN_ENOTSUP;
    /* The native exit ring uses the LVGL API 1.1 indexed-image table. */
    if (ca.ui->struct_size < GM_PLUGIN_LVGL_API_1_1_SIZE ||
        !GM_PLUGIN_VERSION_COMPATIBLE(ca.ui->api_version,
                                      GM_PLUGIN_VERSION(1U, 1U)))
        return GM_PLUGIN_EVERSION;

    ca.have_reading = false;
    ca.contact_bad = false;
    ca.stale = false;
    ca.state = STATE_IDLE;
    ca.bpm = 0U;
    ca.zone = ZONE_NONE;
    ca.interval_ms = 0U;
    ca.stale_elapsed_ms = 0U;
    ca.tracking = false;
    ca.tracking_no_speed = false;
    ca.tracking_sec = 0U;
    ca.speed_dkmh = 0U;
    ca.distance_m = 0U;
    ca.ui_ready = false;

    plugin->abi_version = GM_PLUGIN_ABI_MIN_VERSION;
    plugin->context = &ca;
    plugin->on_start = cardio_start;
    plugin->on_loop = cardio_loop;
    plugin->on_event = cardio_event;
    plugin->on_stop = cardio_stop;
    return GM_PLUGIN_OK;
}
