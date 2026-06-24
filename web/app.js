// Fortress AI — Options Command Center (web)
// Mirrors the Android :trader app. Sample data lives here; swap `DATA` for a
// fetch() against the FastAPI backend (server/main.py, /v1/* routes) to go live.

const DATA = {
  portfolioValue: "$17,821.97",
  todayChange: "+$400.35 Today",
  positions: [
    { ticker: "ORCL", strategy: "Iron Condor",    premium: "$1.79", risk: "HIGH" },
    { ticker: "AAPL", strategy: "Iron Condor",    premium: "$1.25", risk: "LOW" },
    { ticker: "NVDA", strategy: "Bull Put Spread", premium: "$2.10", risk: "MEDIUM" },
  ],
  riskScore: 23,
  earningsExposure: [
    "ORCL — Earnings Tomorrow",
    "NVDA — Earnings This Week",
  ],
  connections: [
    { title: "Broker", value: "Alpaca", connected: true },
    { title: "AI Provider", value: "Gemini", connected: true },
  ],
};

const RISK_COLOR = { LOW: "var(--green)", MEDIUM: "var(--orange)", HIGH: "var(--red)" };
const RISK_LABEL = { LOW: "Low", MEDIUM: "Medium", HIGH: "High" };

function hexFor(varColor) {
  // map CSS var() to an rgba tint for the badge background
  const map = {
    "var(--green)": "0,230,118",
    "var(--orange)": "255,167,38",
    "var(--red)": "255,82,82",
  };
  return map[varColor] || "139,148,158";
}

function render() {
  // Dashboard
  document.getElementById("pv").textContent = DATA.portfolioValue;
  document.getElementById("pv-change").textContent = DATA.todayChange;

  // Portfolio
  document.getElementById("positions").innerHTML = DATA.positions.map((p) => {
    const color = RISK_COLOR[p.risk];
    const rgb = hexFor(color);
    return `
      <div class="card">
        <div class="row">
          <p class="ticker">${p.ticker}</p>
          <span class="badge" style="color:${color};background:rgba(${rgb},0.18);">
            ${RISK_LABEL[p.risk]} Risk
          </span>
        </div>
        <p class="strategy">${p.strategy}</p>
        <p class="premium">Premium: ${p.premium}</p>
      </div>`;
  }).join("");

  // Risk
  document.getElementById("risk-score").textContent = `${DATA.riskScore} / 100`;
  document.getElementById("earnings").innerHTML = DATA.earningsExposure
    .map((e) => `<p class="orange" style="margin:4px 0 0;">${e}</p>`)
    .join("");

  // Settings
  document.getElementById("connections").innerHTML = DATA.connections.map((c) => `
    <div class="card">
      <p class="card-label">${c.title}</p>
      <p class="conn-title">
        <span class="dot" style="background:${c.connected ? "var(--green)" : "var(--gray)"};"></span>
        ${c.value} ${c.connected ? "Connected" : "Disconnected"}
      </p>
    </div>`).join("");
}

function setupTabs() {
  const buttons = document.querySelectorAll("nav.tabbar button");
  buttons.forEach((btn) => {
    btn.addEventListener("click", () => {
      const tab = btn.dataset.tab;
      document.querySelectorAll(".screen").forEach((s) => s.classList.remove("active"));
      document.getElementById(tab).classList.add("active");
      buttons.forEach((b) => b.classList.toggle("active", b === btn));
      document.querySelector(".app").scrollTo?.(0, 0);
      window.scrollTo(0, 0);
    });
  });
}

render();
setupTabs();
