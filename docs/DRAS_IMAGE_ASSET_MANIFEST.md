# DRAS Image Asset Manifest

> Official record of image assets, dimension specifications, composition mappings, and double-exposure treatment for the DRAS 2.0 Emergency Operations Center interface.

---

## Asset Directory Mapping
* **Original Assets Directory**: `design-assets/selected/`
* **Processed Web Assets Directory**: `src/main/resources/static/images/sections/`

---

## Image Asset Specifications

### 1. Command Center
* **Original Filename**: `command-center.jpg.jpg`
* **Target Section**: Command Center (Global EOC Overview)
* **Target Asset Name**: `command-center.jpg`
* **Original Dimensions**: 736 × 919 px
* **Aspect Ratio**: ~4:5 (Portrait)
* **Subject**: High-tension electrical infrastructure, power transmission pole, and intricate converging wires.
* **Symbolism**: System-level electrical grid, interconnectivity, real-time dispatch dependencies.
* **Layout Family**: **Layout A (Wide Photographic Header)**
* **CSS Positioning**: `background-position: right center; background-size: cover;`
* **Blending & Double Exposure Treatment**:
  * Luminance contrast balanced so dark cable silhouettes emerge against `#191A1D`.
  * Left-to-right gradient overlay: `linear-gradient(90deg, #191A1D 20%, rgba(25, 26, 29, 0.75) 60%, rgba(25, 26, 29, 0.3) 100%)`.
  * Effective opacity: 35%.

---

### 2. Resource Inventory
* **Original Filename**: `resource-inventory.jpg.jpg`
* **Target Section**: Resource Inventory
* **Target Asset Name**: `resource-inventory.jpg`
* **Original Dimensions**: 1199 × 801 px
* **Aspect Ratio**: ~3:2 (Landscape)
* **Subject**: Expansive, abandoned industrial warehouse interior with structural depth and high ceilings.
* **Symbolism**: Physical depot space, material stockpiles, emergency reserves, logistics capacity.
* **Layout Family**: **Layout B (Split Composition)**
* **CSS Positioning**: `background-position: center center; background-size: cover;`
* **Blending & Double Exposure Treatment**:
  * Industrial grain preserved; desaturated monochrome.
  * Dual gradient mask: `linear-gradient(180deg, rgba(25, 26, 29, 0.2) 0%, #191A1D 95%)`, with radial falloff.
  * Effective opacity: 30%.

---

### 3. Organization Directory
* **Original Filename**: `organization-directory.jpg.jpg`
* **Target Section**: Organization Directory
* **Target Asset Name**: `organization-directory.jpg`
* **Original Dimensions**: 1200 × 675 px
* **Aspect Ratio**: 16:9 (Landscape)
* **Subject**: Modern hospital facility facade with sharp architectural geometry and glass framing.
* **Symbolism**: Institutional disaster readiness, medical relief partners, registered agencies.
* **Layout Family**: **Layout B (Split Composition)**
* **CSS Positioning**: `background-position: center right; background-size: cover;`
* **Blending & Double Exposure Treatment**:
  * Tamed architectural highlights to prevent competition with typography.
  * Left-to-right mask: `linear-gradient(90deg, #191A1D 30%, rgba(25, 26, 29, 0.8) 70%, rgba(25, 26, 29, 0.3) 100%)`.
  * Effective opacity: 35%.

---

### 4. Requests Queue
* **Original Filename**: `requests-queue.jpg.jpg`
* **Target Section**: Requests Queue
* **Target Asset Name**: `requests-queue.jpg`
* **Original Dimensions**: 735 × 490 px
* **Aspect Ratio**: ~3:2 (Landscape)
* **Subject**: Operations control room in low-light, illuminated by tactical display screens and world map projection.
* **Symbolism**: Continuous queue monitoring, operational prioritization, incoming emergency requests.
* **Layout Family**: **Layout A (Wide Photographic Header)**
* **CSS Positioning**: `background-position: center center; background-size: cover;`
* **Blending & Double Exposure Treatment**:
  * Display screen luminescences maintained without introducing saturated hues.
  * Bottom and side vignetting blending into `#191A1D`.
  * Effective opacity: 32%.

---

### 5. Allocation Center
* **Original Filename**: `allocation-center.jpg.jpg`
* **Target Section**: Allocation Center
* **Target Asset Name**: `allocation-center.jpg`
* **Original Dimensions**: 1200 × 861 px
* **Aspect Ratio**: ~7:5 (Landscape)
* **Subject**: Emergency responders and ambulances staged for immediate deployment.
* **Symbolism**: Active resource dispatch, physical asset allocation, rapid incident response.
* **Layout Family**: **Layout A (Wide Photographic Header)**
* **CSS Positioning**: `background-position: center right; background-size: cover;`
* **Blending & Double Exposure Treatment**:
  * White border artifact trimmed; subject centered on emergency vehicles.
  * Deep charcoal gradient overlay from left: `linear-gradient(90deg, #191A1D 35%, rgba(25, 26, 29, 0.7) 100%)`.
  * Effective opacity: 35%.

---

### 6. Scheduling Simulator
* **Original Filename**: `scheduling-simulator.jpg.jpg`
* **Target Section**: Scheduling Simulator (FCFS / Priority / Round Robin)
* **Target Asset Name**: `scheduling-simulator.jpg`
* **Original Dimensions**: 601 × 800 px
* **Aspect Ratio**: ~3:4 (Portrait)
* **Subject**: Complex vintage clockwork mechanism with interlocking gears and Roman numerals.
* **Symbolism**: Deterministic time sequencing, quantum slicing, scheduling order, execution timing.
* **Layout Family**: **Layout B (Split Composition)**
* **CSS Positioning**: `background-position: right center; background-size: contain; background-repeat: no-repeat;`
* **Blending & Double Exposure Treatment**:
  * Gear tooth micro-details preserved via high-pass contrast adjustment.
  * Radial gradient fade into `#191A1D`.
  * Effective opacity: 30%.

---

### 7. Banker's Algorithm Simulator
* **Original Filename**: `bankers-simulator.jpg.jpg`
* **Target Section**: Banker's Algorithm Simulator (Deadlock Avoidance)
* **Target Asset Name**: `bankers-simulator.jpg`
* **Original Dimensions**: 609 × 1000 px
* **Aspect Ratio**: ~3:5 (Portrait)
* **Subject**: Interconnected network of human silhouettes connected by fine structural lines.
* **Symbolism**: Competing processes, claim graphs, resource allocation states, circular wait avoidance.
* **Layout Family**: **Layout C (Abstract Composition)**
* **Special Treatment**:
  * **Embedded Checkerboard Pattern Removal**: The faux-transparency checkerboard pattern is removed/masked using alpha thresholding and dark luminance mapping so only the silhouettes and network vectors remain visible over `#191A1D`.
* **CSS Positioning**: `background-position: right center; background-size: contain; background-repeat: no-repeat;`
* **Blending & Double Exposure Treatment**:
  * Isolated network structure rendered with luminous silver accents (`#8DAFC4` / `#A5A7AD`).
  * Effective opacity: 35%.

---

### 8. Performance Metrics
* **Original Filename**: `performance-metrics.jpg.jpg`
* **Target Section**: Performance Metrics & Simulation Analytics
* **Target Asset Name**: `performance-metrics.jpg`
* **Original Dimensions**: 736 × 554 px
* **Aspect Ratio**: ~4:3 (Landscape)
* **Subject**: Abstract signal waveform displaying harmonic peaks, troughs, and data amplitude oscillations.
* **Symbolism**: Statistical throughput, response latency, turnaround distribution, operational telemetry.
* **Layout Family**: **Layout C (Abstract Composition)**
* **Special Treatment**:
  * **Solid White Background Inversion/Transformation**: Converted to graphite `#191A1D` background with subtle light grey/silver waveform filaments (`#F4F4F2`).
* **CSS Positioning**: `background-position: center right; background-size: cover;`
* **Blending & Double Exposure Treatment**:
  * Positioned softly behind analytical headers and Gantt comparison charts.
  * Effective opacity: 20%.

---

### 9. System Status
* **Original Filename**: `system-status.jpg.jpg`
* **Target Section**: System Status (DBMS & PostgreSQL Diagnostics)
* **Target Asset Name**: `system-status.jpg`
* **Original Dimensions**: 768 × 1408 px
* **Aspect Ratio**: ~1:2 (Tall Portrait)
* **Subject**: Massive vintage server racks, patch panels, cabling bundles, and indicator lights.
* **Symbolism**: Database reliability, WAL durability, ACID transactional guarantees, low-level architecture.
* **Layout Family**: **Layout B (Split Composition)**
* **CSS Positioning**: `background-position: top right; background-size: cover;`
* **Blending & Double Exposure Treatment**:
  * Tall portrait proportions preserved without stretching (`object-fit: cover`).
  * Gradient overlay: `linear-gradient(90deg, #191A1D 40%, rgba(25, 26, 29, 0.7) 100%)`.
  * Effective opacity: 25%.
