# MotionPad

Transforma um celular Android num **Joy-Con (R) com giroscópio** para o Nintendo Switch, via Bluetooth, **sem root**. Feito para jogar *Switch Sports*.

> ⚠️ Projeto experimental, não afiliado à Nintendo. "Joy-Con" e "Nintendo Switch" são marcas da Nintendo; o nome é usado só porque o Switch exige esse nome no Bluetooth para aceitar o controle.

## Status

| Parte | Situação |
|---|---|
| Pareamento com o Switch | ✅ funciona (no HyperOS precisou de ajuda via adb, ver abaixo) |
| Handshake completo (info, SPI, modo 0x30, IMU, luzes, vibração) | ✅ |
| Botões e analógico | ✅ chegam ao Switch |
| Giroscópio/acelerômetro (IMU modo 1 e **modo 2 / quaternion**) | ✅ implementado, ⏳ falta validar dentro do jogo |
| Vibração (HD Rumble → vibrador do celular) | ✅ aproximada |
| Reconexão automática ao último Switch | ✅ |
| **Switch Sports** | ❌ ainda não passa da tela de controles: o jogo exige um **par** de Joy-Cons (L + R) para o jogador 1, e um celular só emula um lado. Ver [Próximos passos](#próximos-passos). |

Testado em: POCO F6 Pro (HyperOS 3, Android 16) + Nintendo Switch Lite.

## Como funciona

```
Celular (Android 9+)                              Nintendo Switch
┌───────────────────────────┐   Bluetooth HID    ┌──────────────────┐
│ MotionPad                 │ ◄────────────────► │ procura "Joy-Con │
│  ├ BluetoothHidDevice     │  relatório 0x30    │ (R)" em Mudar    │
│  ├ protocolo Joy-Con      │  a cada 15 ms      │ empunhadura/ordem│
│  ├ giroscópio 480 Hz      │  subcomandos 0x01  │                  │
│  └ vibração               │  rumble 0x10       │                  │
└───────────────────────────┘                    └──────────────────┘
```

- **Bluetooth:** usa a API pública `BluetoothHidDevice` (Android 9+). O app registra um descritor HID igual ao do controle oficial e troca o nome do Bluetooth para `Joy-Con (R)`.
- **Protocolo:** responde aos subcomandos do Switch (0x02 info do aparelho, 0x10 leitura de SPI com calibração, 0x03 modo, 0x40 IMU, 0x48 vibração, 0x30 luzes...). A memória SPI é emulada em [`SpiFlash.kt`](app/src/main/java/com/motionpad/app/SpiFlash.kt) com calibração "limpa" (4096 = 1 G; 13371/936 LSB por °/s).
- **Movimento:** [`MotionSensor.kt`](app/src/main/java/com/motionpad/app/MotionSensor.kt) **integra** o giroscópio (ângulo girado) em vez de mandar só a velocidade. Assim a rotação total recebida pelo Switch é exata mesmo com variação de tempo no Bluetooth.
  - **Modo 1** (dados brutos): 3 amostras de aceleração + velocidade angular por relatório.
  - **Modo 2** (o que o Switch atual pede): o controle integra a orientação num **quaternion** e envia compactado em bits. Formato portado do projeto [switch-pico](https://github.com/jyapayne/switch-pico) (MIT). Mandar dados do modo 1 quando o Switch pede o modo 2 faz o jogo ver rotação aleatória.
- **Eixos:** celular em pé, tela para cima, topo apontando para a TV = Joy-Con apontado para a TV. `Joy-Con X = celular Y`, `Joy-Con Y = celular X`, `Joy-Con Z = −celular Z`. A opção "Eixos alternativos" gira 180° no eixo longo.

### Código

| Arquivo | O que faz |
|---|---|
| [`JoyConEmulator.kt`](app/src/main/java/com/motionpad/app/JoyConEmulator.kt) | Bluetooth HID, subcomandos, envio a cada 15 ms, codificação da IMU (modo 1 e 2) |
| [`SpiFlash.kt`](app/src/main/java/com/motionpad/app/SpiFlash.kt) | Memória SPI virtual (calibração, cores, parâmetros) |
| [`MotionSensor.kt`](app/src/main/java/com/motionpad/app/MotionSensor.kt) | Giroscópio/acelerômetro na taxa máxima, integração |
| [`Rumble.kt`](app/src/main/java/com/motionpad/app/Rumble.kt) | HD Rumble → vibrador do celular |
| [`MainActivity.kt`](app/src/main/java/com/motionpad/app/MainActivity.kt), [`PadViews.kt`](app/src/main/java/com/motionpad/app/PadViews.kt) | Interface: botões, analógico, log de diagnóstico |
| [`tools/adb-helpers/`](tools/adb-helpers) | Programas que rodam como `shell` via adb (ver abaixo) |

## Compilar

Requisitos: JDK 17+ e Android SDK (plataforma 35, build-tools 35). Gradle 8.10+.

```bash
echo "sdk.dir=/caminho/para/Android/Sdk" > local.properties
gradle assembleDebug
# APK em app/build/outputs/apk/debug/app-debug.apk
```

## Usar

1. Instale o APK e abra o MotionPad. Aceite "Dispositivos próximos".
2. No Switch: **Controles → Mudar empunhadura/ordem**.
3. No app: **Parear** e aceite deixar o celular visível. Volte para o app.
4. Se aparecer "Parear com Nintendo Switch?", aceite e volte para o app.
5. Nas próximas vezes: abra o app e toque **Reconectar** (ou ele reconecta sozinho).

**Mantenha o MotionPad aberto na tela.** O Android cancela o modo controle (HID) assim que o app sai do primeiro plano (ex.: abrir a câmera). O app re-registra sozinho ao voltar.

## Problemas encontrados (e soluções)

Documentado na ordem em que apareceram, para quem for tentar em outros aparelhos.

1. **"HID registrado=false" depois de tocar em Parear.** A janela do sistema "tornar visível" tira o app do primeiro plano e o `HidDeviceService` desregistra o app. → O app re-registra em `onResume`.
2. **Classe do Bluetooth (CoD) = telefone (0x5A020C).** No HyperOS 3 / Android 16 ela vem da propriedade somente-leitura `bluetooth.device.class_of_device`; `setprop` é negado, `settings put global bluetooth_class_of_device` é negado (sem `WRITE_SECURE_SETTINGS`) e `IBluetooth.setBluetoothClass` não existe mais. **Mas não era o problema:** o Switch encontra e conecta mesmo com classe de telefone — ele filtra pelo **nome**.
3. **O HyperOS trocava o nome de volta** (ex.: ao abrir Configurações → Bluetooth). → O app reaplica `Joy-Con (R)` ao voltar para a tela.
4. **Pareamento cancelado em silêncio.** O app Configurações do HyperOS (`BluetoothPairingRequest.onReceive`) cancela qualquer pareamento de aparelhos que estejam na **lista de bloqueio de Bluetooth** (SharedPreferences `BlackfileForBluetoothDevice`). O Switch estava nela. → Remover o Switch da lista em Configurações → Bluetooth → dispositivos bloqueados. A tela pode ser aberta via adb:
   ```bash
   adb shell am start -n com.android.settings/.SubSettings --es :settings:show_fragment com.android.settings.bluetooth.BluetoothBlacklistFragment
   ```
5. **Confirmar o pareamento automaticamente.** O usuário `shell` do adb tem `BLUETOOTH_PRIVILEGED`, então [`PairHelper`](tools/adb-helpers/PairHelper.java) chama `setPairingConfirmation(true)` assim que o Switch inicia o pareamento.
6. **Switch repetia o subcomando 0x21 (~400×).** A resposta à configuração do MCU de NFC/IR estava errada. → Responder só com ACK `0x80` (igual ao switch-pico).
7. **Jogo travado/movimento aleatório.** O Switch liga a IMU com `0x40 0x02` (modo quaternion) e o app mandava o formato do modo 1. → Implementado o modo 2.
8. **Switch Sports pede L + R.** Ver abaixo.

### adb sem cabo (HyperOS)

No POCO a janela "Permitir depuração USB?" nunca apareceu. Funcionou pela **Depuração por Wi-Fi**: abra "Parear com código de pareamento" no celular e use o mDNS do adb para achar a porta:

```bash
adb mdns services          # mostra _adb-tls-pairing._tcp  IP:PORTA
adb pair IP:PORTA CODIGO
adb connect IP:PORTA_DA_TELA_PRINCIPAL
```

### Ajudantes via adb

```bash
ANDROID_HOME=/caminho/do/sdk tools/adb-helpers/build.sh
# confirmar pareamento por 5 min (endereço do Switch aparece no logcat ao conectar):
adb shell CLASSPATH=/data/local/tmp/pairhelper.dex app_process /system/bin PairHelper XX:XX:XX:XX:XX:XX 300
```

`SetCod.java` foi a tentativa de mudar a classe do Bluetooth; **não funciona no Android 16** (método removido) e ficou só como registro.

## Próximos passos

- [ ] **Modo Joy-Con (L)** no app (direcional, L/ZL/−/Capture, eixos do lado esquerdo), para usar **dois celulares** como par e passar da tela de controles do Switch Sports. Alternativa: um Joy-Con (L) de verdade + celular como (R).
- [ ] Validar o modo 2 da IMU dentro do jogo e ajustar sensibilidade/eixos.
- [ ] Mostrar na tela do app quais botões o Switch está recebendo.
- [ ] Ícone e interface melhores.

## Créditos

- [dekuNukem/Nintendo_Switch_Reverse_Engineering](https://github.com/dekuNukem/Nintendo_Switch_Reverse_Engineering) — protocolo Bluetooth, subcomandos, SPI.
- [jyapayne/switch-pico](https://github.com/jyapayne/switch-pico) (MIT) — formato do IMU modo 2 (quaternion) e resposta ao 0x21.
- [ndeadly/MissionControl](https://github.com/ndeadly/MissionControl) — referência de como o Switch filtra controles por nome/classe.
- Descritor HID e SPI baseados em joycontrol / nxbt.
