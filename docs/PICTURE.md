# Picture Quality (PQ) Settings Inspector

Inspect, diff, and live-monitor picture quality settings per input (HDMI 1–4, Tuner, Apps) on Fire TV and MediaTek televisions over ADB with no root.

## Why this exists

Smart televisions apply aggressive post-processing pipelines to incoming signals: AI Super Resolution (`AISR`), polyphase scaling ringing, 2D high-pass spatial peaking (`SR_Strength`), dynamic contrast (`DLC`), and temporal motion smoothing (`MEMC`).

For dedicated roles like **PC gaming** (low latency, 4:4:4 chroma, integer scaling) or **ambient photo displays** (clean reproduction without JPEG macroblock halos), you want to know *exactly* what hardware registers are active on a specific input, what the factory baseline preset was, and how user adjustments affect the signal in real time.

## Usage

### 1. Inspect Active Input & Live Settings
Detects whatever input is currently playing on screen, identifies its picture mode and signal format (SDR, HDR10, Dolby Vision), and displays active parameters alongside factory baseline defaults:

```bash
./scripts/picture.sh
```

Example output:
```text
Active Port: HDMI 2 (Session ID: com.mediatek.tis/.HdmiInputService/HW3)
Active Mode: PC · Signal: SDR
Preset Source Key: HDMI2

=== Live Picture Settings (HDMI 2) ===

Setting                                Live / Active    Preset Default  
------------------------------------------------------------------------
[Mode & Gaming]
  Picture Mode                         PC               PC                 
  Auto Low Latency Mode (ALLM)         On               -                  
  PC Mode (4:4:4 bypass)               -                On                 

[Clarity & Sharpness]
  Sharpness (VIP Peaking)              5                0                * 
  Super Resolution (AISR)              -                Off                
  Edge Enhancer                        Off              Off                
  Ultra Clear Detail                   Off              Low              * 

[Noise Reduction]
  MPEG Noise Reduction (DBK)           Off              Off                
  Noise Reduction (Spatial/Temporal)   Off              Off                
  Debanding Filter (ABF)               -                Middle             

[Motion & Cadence]
  Motion Smoothing (MEMC)              Off              Off                
  De-judder                            -                0                  
  De-blur                              -                0                  
  Natural Cinema (24p pull-down)       -                Off                

[Contrast & Brightness]
  Backlight Level                      85               85                 
  Brightness (Black Level)             0                0                  
  Contrast (White Level)               50               50                 
  Local Dimming                        -                Low                
  Dynamic Contrast                     Off              Middle           * 
  Local Contrast Enhancement           Off              Off                
  HDR Contrast Enhancer                Off              Off                
  Gamma Curve                          -                Level_06           

[Color & Tint]
  Color Temperature                    -                Standard           
  Color Saturation                     5                0                * 
  Tint / Hue                           0                0                  
  Dynamic Color                        Off              Off                
  Flesh Tone Correction                Off              Off                
```

### 2. Live Monitoring While Using the Remote (`--watch`)
Stream parameter and input changes in real time as you adjust them using the TV remote or quick settings:

```bash
./scripts/picture.sh --watch
```

Output:
```text
Monitoring Picture Quality changes in real time... (Ctrl+C to stop)
[11-21 08:51:11.5] INPUT SWITCH: Current Source -> HDMI2
[11-21 08:51:16.8] SET: Picture_Mode              = PC
[11-21 08:53:45.0] SET: MPEG_NR                   = Off
[11-21 08:53:45.0] SET: ALLM                      = On
[11-21 08:53:45.0] SET: SR_Strength               = 0
```

### 3. Diff Picture Modes or Inputs (`--diff`)
Compare the baseline hardware registers between two picture modes or two inputs side by side:

```bash
# Compare PC mode vs Standard mode on active input:
./scripts/picture.sh --diff PC Standard

# Compare HDMI 1 vs HDMI 2:
./scripts/picture.sh --diff HDMI1 HDMI2 --mode PC
```

### 4. Inspect Factory Baseline Presets (`--preset`)
Examine the vendor-defined preset profile for any input, mode, and HDR format without needing the input to be active:

```bash
./scripts/picture.sh --preset HDMI2 --mode Game --hdr HDR10
```

### 5. Automation / Machine-Readable Output (`--json`)
Export full active parameters and preset baselines in structured JSON:

```bash
./scripts/picture.sh --json
```

---

## Technical Details

On MediaTek Fire TV platforms (merak/mt5896/mt5873 architecture), picture settings are structured across three layers:

1. **Vendor Preset Databases (`/vendor/tvconfig/.../presetdb/*.json`):** World-readable factory baseline files defining 120 parameters per mode across 25 picture modes (`Standard`, `PC`, `Custom`, `Game`, `Movie`, `DolbyVision...`) and all HDR formats.
2. **Persistent User Database (`/data/user/0/com.mediatek.customerpq/databases/pq.db`):** SQLite database tracking user-customized overrides per input.
3. **Hardware Coprocessor HAL (`pqu_mt5896_2048.elf` & `CustomerPqService`):** Managed via `com.mediatek.customerpq` and `com.amazon.tv.settings.mediatek/.OEMService`, dispatches live parameter loads to the hardware Picture Quality Unit (PQU).

For a complete hardware pipeline teardown, PQU RISC-V coprocessor firmware disassembly, and low-level register maps, see **[MediaTek MT5896 Video Rendering & PQ Pipeline](devices/mediatek-mt5896.md)**.
