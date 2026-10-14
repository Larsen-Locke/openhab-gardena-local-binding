# GARDENA Binding (local)

This binding controls GARDENA smart system devices through the **local** WebSocket API of the GARDENA smart Gateway.
It needs no GARDENA cloud connection, no Husqvarna developer account and no API key.
Valve states and sensor values arrive as soon as the gateway pushes them, without polling.

The local API is GARDENA's own, currently marked as a preview by GARDENA.
It is switched off on the gateway by default; see [Preparing the Gateway](#preparing-the-gateway).

## Supported Things

| Thing type           | Type   | Devices                                                                                  |
|----------------------|--------|------------------------------------------------------------------------------------------|
| `gateway`            | Bridge | GARDENA smart Gateway (19005)                                                            |
| `irrigation-control` | Thing  | smart Irrigation Control, first generation (19032) and second generation (19035)         |
| `water-control`      | Thing  | smart Water Control, first generation (19031) and second generation (19033); smart Pipeline Water Control (19050) |
| `dual-water-control` | Thing  | smart Dual Water Control (19034)                                                         |
| `sensor`             | Thing  | smart Sensor (19030) and smart Sensor II (19040)                                         |

Mowers, the smart Pump and the smart Power Adapter are reported by the gateway as well, but are not supported yet.
They are simply not offered in the Inbox.

Verified on real hardware so far: a first-generation smart Irrigation Control (19032) and a smart Sensor II (19040) on openHAB 5.2.1.
The water controls and second-generation devices follow GARDENA's reference implementation but have not been run against real hardware yet.

## Preparing the Gateway

The WebSocket service on the gateway has to be enabled once:

1. Open the gateway's web interface, e.g. `https://GARDENA-123456.local` or `https://192.168.1.20`.
   The browser warns about the gateway's self-signed certificate; accept it.
2. Log in with the gateway's password: the first block of the gateway ID printed on the back of the gateway.
   For the ID `1234abcd-996c-48f7-83dc-d2d1bac08e7e` the password is `1234abcd`.
3. Open the advanced options behind the small grey arrow at the bottom of the page and enable the WebSocket API.

Alternatively with `curl`, from any machine in the same network:

```shell
gateway=192.168.1.20
password=1234abcd
session=$(curl -s --insecure -H 'Content-Type: application/json' -d '{"password": "'"$password"'"}' https://$gateway/login | jq -r .session)
curl --insecure -X PUT -H "X-session: $session" -H 'Content-Type: application/json' -d '{"enable": true}' https://$gateway/websocket_api
```

The gateway then accepts WebSocket connections on port 8443.
The GARDENA app and cloud keep working alongside the local API.

## Discovery

The gateway announces itself via mDNS (`_gardena-smart._tcp`) and shows up in the Inbox.
Only the password has to be added after approving it.

Devices are proposed only while a scan is running: press "Scan" for the GARDENA Binding (local) in the Inbox once the gateway is online.
Every supported device paired with the gateway that has no thing yet is proposed.

Devices are paired with the gateway in the GARDENA app, as before.

## Thing Configuration

### `gateway` Thing Configuration

| Name       | Type    | Description                                                                     | Default | Required | Advanced |
|------------|---------|---------------------------------------------------------------------------------|---------|----------|----------|
| `host`     | text    | IP address or host name of the gateway.                                         | -       | yes      | no       |
| `password` | text    | The first block of the gateway ID printed on the back of the gateway.           | -       | yes      | no       |
| `port`     | integer | Port of the gateway's WebSocket service.                                        | 8443    | no       | yes      |
| `refreshInterval` | integer | How often the device list is read again, in minutes; `0` turns it off.   | 10      | no       | yes      |

The gateway pushes an event only when a value changes.
A device that reports the same values again only refreshes timestamps on the gateway, so the binding reads the whole device list every `refreshInterval` minutes to keep `last-seen` current.
The request is a few kilobytes and answered within a fraction of a second.

The gateway uses a self-signed certificate that does not match its IP address, so the binding does not verify the certificate.
The password authenticates openHAB to the gateway; nothing authenticates the gateway to openHAB.
GARDENA's own Home Assistant integration makes the same trade-off.

If the gateway rejects the password, the thing goes to `CONFIGURATION_ERROR` and the binding stops trying.
Retrying a wrong password only produces failed logins on the gateway; save the corrected configuration to try again.

### `irrigation-control`, `water-control` and `dual-water-control` Thing Configuration

| Name               | Type    | Description                                                                         | Default | Required |
|--------------------|---------|-------------------------------------------------------------------------------------|---------|----------|
| `deviceId`         | text    | The device's ID on the gateway, 24 hexadecimal digits. Filled in by discovery.      | -       | yes      |
| `wateringDuration` | integer | How long a valve stays open when switched `ON`, in minutes (1-600), until its `duration` channel says otherwise. | 30      | no       |

### `sensor` Thing Configuration

| Name       | Type | Description                                                                    | Default | Required |
|------------|------|--------------------------------------------------------------------------------|---------|----------|
| `deviceId` | text | The device's ID on the gateway, 24 hexadecimal digits. Filled in by discovery. | -       | yes      |

The device ID is the device's SGTIN-96 in hexadecimal, e.g. `3034F8EE90126D400001737D`.
It is not the serial number printed on the device; use discovery to find it.

## Channels

### Valve Channels

Things with valves have one channel group per valve: `valve1` to `valve6` on the irrigation control, `valve1` on the water control and `valve1`, `valve2` on the dual water control.

| Channel     | Type        | Read/Write | Things                   | Description                                                                          |
|-------------|-------------|------------|--------------------------|--------------------------------------------------------------------------------------|
| `state`     | Switch      | RW         | all with valves          | `ON` while the valve is open. `ON` opens it for the valve's `duration`, `OFF` closes it. |
| `duration`  | Number:Time | RW         | all with valves          | How long the valve stays open when switched `ON`, e.g. `15 min`. A plain number means minutes. Setting it does not open the valve. |
| `name`      | String      | R          | `irrigation-control`     | The valve name set in the GARDENA app.                                               |
| `connected` | Switch      | R          | `irrigation-control`     | `ON` if a valve is wired to this output.                                             |
| `error`     | Number      | R          | all with valves          | The valve's error code, `0` for none.                                                |

To water for a particular time, set `duration` first and then switch `state` `ON`.
`duration` is not persisted: after a restart every valve starts from the thing's `wateringDuration` again.

Every watering run has a duration, and the device closes the valve by itself when it is over.
A lost connection or an openHAB restart therefore never leaves the water running.
The binding accepts runs of up to 10 hours.

### Device Channels

On things with valves these channels are in the `device` group; on the `sensor` thing they have no group.

| Channel               | Type                 | Read/Write | Things                                   | Description                                                        |
|-----------------------|----------------------|------------|------------------------------------------|--------------------------------------------------------------------|
| `soil-moisture`       | Number:Dimensionless | R          | `sensor`                                 | Soil moisture in percent.                                          |
| `soil-temperature`    | Number:Temperature   | R          | `sensor`                                 | Soil temperature.                                                  |
| `ambient-temperature` | Number:Temperature   | R          | `sensor`                                 | Air temperature (smart Sensor only).                               |
| `light-intensity`     | Number:Illuminance   | R          | `sensor`                                 | Light intensity in lux (smart Sensor only).                        |
| `temperature`         | Number:Temperature   | R          | `water-control`, `dual-water-control`    | Ambient temperature at the water control, meant for frost detection. |
| `frost-warning`       | Switch               | R          | `sensor`, `water-control`, `dual-water-control` | `ON` while the temperature is below about 5 °C (first-generation devices). |
| `battery-level`       | Number:Dimensionless | R          | `sensor`, `water-control`, `dual-water-control` | Battery charge in percent.                                         |
| `rf-link-quality`     | Number:Dimensionless | R          | all devices                              | Radio link quality in percent (first-generation devices).          |
| `signal-strength`     | Number:Power         | R          | `irrigation-control`, `water-control`, `dual-water-control` | Radio signal strength in dBm (second-generation devices). |
| `last-seen`           | DateTime             | R          | all devices                              | When the device last reported anything to the gateway.             |

A value a device model does not report is never set, rather than set to `UNDEF`.
First-generation devices report `rf-link-quality` and `frost-warning`, second-generation devices `signal-strength`.

Sensors measure on their own schedule, and the values arrive when they do.
A `REFRESH` answers with the last reported value, since there is nothing to poll.

The gateway's thing has no channels.
A device the gateway cannot reach over the radio is `OFFLINE` with a `COMMUNICATION_ERROR`; a device no longer paired with the gateway is `OFFLINE`/`GONE`.

## Full Example

### `gardena.things`

```java
Bridge gardenalocal:gateway:garden "GARDENA Gateway" [ host="192.168.1.20", password="1234abcd" ] {
    Thing irrigation-control irrigation "Bewässerung"  [ deviceId="3034F8EE90126D400001737D", wateringDuration=20 ]
    Thing sensor             lawnSensor "Rasensensor"  [ deviceId="3034F8EE901267400000B333" ]
}
```

### `gardena.items`

```java
Switch               Valve1         "Rasen vorn [%s]"            { channel="gardenalocal:irrigation-control:garden:irrigation:valve1#state" }
Number:Time          Valve1Duration "Rasen vorn Dauer [%d min]"  { channel="gardenalocal:irrigation-control:garden:irrigation:valve1#duration", unit="min" }
Switch               Valve2         "Beet [%s]"                  { channel="gardenalocal:irrigation-control:garden:irrigation:valve2#state" }
Number:Dimensionless SoilMoisture   "Bodenfeuchte [%d %%]"       { channel="gardenalocal:sensor:garden:lawnSensor:soil-moisture" }
Number:Temperature   SoilTemp       "Bodentemperatur [%.1f °C]"  { channel="gardenalocal:sensor:garden:lawnSensor:soil-temperature" }
Number:Temperature   AirTemp        "Lufttemperatur [%.1f °C]"   { channel="gardenalocal:sensor:garden:lawnSensor:ambient-temperature" }
Number:Illuminance   Light          "Licht [%d lx]"              { channel="gardenalocal:sensor:garden:lawnSensor:light-intensity" }
Number               SensorBattery  "Batterie Sensor [%d %%]"    { channel="gardenalocal:sensor:garden:lawnSensor:battery-level" }
```

### Rule Example

Water the lawn for 15 minutes when the soil is dry in the morning:

```java
rule "Rasen bei Trockenheit bewässern"
when
    Time cron "0 0 6 * * ?"
then
    if ((SoilMoisture.state as QuantityType<Dimensionless>).intValue < 25) {
        Valve1Duration.sendCommand("15 min")
        Valve1.sendCommand(ON)
    }
end
```

## Troubleshooting

| Status                                                   | Meaning                                                                                 |
|----------------------------------------------------------|-----------------------------------------------------------------------------------------|
| "The gateway refused the connection"                     | The WebSocket API is not enabled; see [Preparing the Gateway](#preparing-the-gateway). |
| "The gateway rejected the password"                      | Wrong password; it is the first block of the gateway ID on the back of the gateway.    |
| "Lost the connection to the gateway (...), retrying..."  | Network problem or gateway restart; the binding reconnects with a growing delay of 5 to 60 seconds. |

`DEBUG` logging for `io.github.larsenlocke.binding.gardena` shows the connection's life cycle, `TRACE` every message exchanged with the gateway.
The password never appears in the log.

## Building

```shell
mvn clean install
```

This is a **standalone** Maven module that uses `bnd-maven-plugin` to produce an OSGi bundle, rather than part of the official `openhab-addons` reactor build.
It depends on openHAB core artifacts resolved from the openHAB JFrog repository, configured in `pom.xml`.
Adjust `openhab.core.version` in `pom.xml` to match the openHAB version you are running.

Drop the resulting `target/io.github.larsenlocke.binding.gardena-*.jar` into your openHAB `addons/` folder.
Remove the previous `io.github.larsenlocke.binding.gardena-*.jar` from `addons/` first, otherwise openHAB loads two versions side by side.

`mvn verify` also runs the checks openHAB applies to its official add-ons: Spotless for the code style (`mvn spotless:apply` fixes it) and the static code analysis (Checkstyle, PMD, SpotBugs), whose report ends up in `target/code-analysis/report.html`.
`sat/` holds the rule parameters and general suppressions of openhab-addons.

`mvn test` runs the unit tests, including one against a stand-in gateway that checks login, the device list, a valve command and reconnecting.

The binding ID `gardenalocal` differs from the cloud binding's `gardena`, so both can be installed at the same time while migrating.

## Migrating from the Cloud Binding

The cloud binding's `commandDuration` plus `start_seconds_to_override`/`stop_until_next_task` pair maps onto `duration` plus `state`: link the duration item to `valveN#duration` and the switch item to `valveN#state`, both with the default profile.
The cloud binding's `activity` (`CLOSED`, `MANUAL_WATERING`, `SCHEDULED_WATERING`) has no counterpart; `state` reports open or closed directly.

## Roadmap

1. smart Power Adapter
2. smart Pump
3. SILENO mowers
4. Remaining watering time, pausing schedules

## Credits

The GARDENA smart local API and its message format are documented by GARDENA's own open-source projects, [gardena-smart-local-api](https://github.com/cloudless-garden/gardena-smart-local-api) and [ha-gardena-smart-local-preview](https://github.com/cloudless-garden/ha-gardena-smart-local-preview).
This binding is an independent Java implementation of the same protocol; see [NOTICE](NOTICE).

This is an independent add-on, not part of the official openHAB distribution, and not affiliated with, authorized or endorsed by GARDENA or Husqvarna; "GARDENA" is a name of its respective owner.

Most of the code, the tests and the documentation were written with the help of an AI coding assistant (Claude by Anthropic). The maintainer reviews the changes and tests every release on real hardware before publishing it.
