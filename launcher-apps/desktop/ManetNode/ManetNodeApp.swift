import SwiftUI
import AppKit

// ManetNode desktop launcher (Mac). Shares the iPhone app's node list and look; pages open in the browser.
// ManetNode launcher: lists OpenMANET nodes, shows each node's status-light colour, and opens
// its OpenManet (8080) or FieldExtension (8787) web page, so nobody has to remember addresses.
// Same look as the Field Extension web page and the ATAK plugin (web/style.css palette).

enum Palette {
    static let bg = Color(red: 0x05 / 255, green: 0x08 / 255, blue: 0x0a / 255)
    static let panel = Color(red: 0x0a / 255, green: 0x0f / 255, blue: 0x13 / 255)
    static let line = Color(red: 0, green: 229 / 255, blue: 1).opacity(0.28)
    static let text = Color(red: 0xd9 / 255, green: 0xe6 / 255, blue: 0xed / 255)
    static let sub = Color(red: 0x8f / 255, green: 0xa7 / 255, blue: 0xb2 / 255)
    static let muted = Color(red: 0x5c / 255, green: 0x76 / 255, blue: 0x82 / 255)
    static let cyan = Color(red: 0, green: 229 / 255, blue: 1)
    static let ok = Color(red: 0, green: 230 / 255, blue: 118 / 255)
    static let bad = Color(red: 1, green: 59 / 255, blue: 77 / 255)

    static func led(_ name: String?) -> Color {
        switch name {
        case "magenta": return Color(red: 0xb5 / 255, green: 0x6c / 255, blue: 1)
        case "green": return ok
        case "cyan": return cyan
        case "red": return bad
        case "amber": return Color(red: 1, green: 0xb3 / 255, blue: 0)
        case "blue": return Color(red: 0x4d / 255, green: 0x8b / 255, blue: 1)
        case "white": return .white
        default: return muted
        }
    }
}

let fePort = 8787, openManetPort = 8080
let defaultNode = "10.41.0.1"   // OpenMANET's own LAN address; iOS cannot read the Wi-Fi's DHCP server

struct NodeStatus {
    var name: String?
    var ledColour: String?
    var battery: Int?
    var meshNodes: Int?
}

@MainActor
final class NodeStore: ObservableObject {
    @Published var saved: [String] = UserDefaults.standard.stringArray(forKey: "saved") ?? []
    @Published var status: [String: NodeStatus?] = [:]   // missing = checking, nil = not answering

    var hosts: [String] {
        var out = [defaultNode]
        for h in saved where !out.contains(h) { out.append(h) }
        return out
    }

    func add(_ raw: String) {
        var h = raw.trimmingCharacters(in: .whitespaces)
        h = h.replacingOccurrences(of: "http://", with: "").replacingOccurrences(of: "https://", with: "")
        if let cut = h.firstIndex(where: { $0 == ":" || $0 == "/" }) { h = String(h[..<cut]) }
        guard !h.isEmpty, !hosts.contains(h) else { return }
        saved.append(h)
        UserDefaults.standard.set(saved, forKey: "saved")
        Task { await refresh() }
    }

    func remove(_ h: String) {
        saved.removeAll { $0 == h }
        status[h] = nil
        UserDefaults.standard.set(saved, forKey: "saved")
    }

    func refresh() async {
        await withTaskGroup(of: (String, NodeStatus?).self) { group in
            for h in hosts { group.addTask { (h, await Self.fetch(h)) } }
            for await (h, s) in group { status[h] = .some(s) }
        }
    }

    nonisolated static func fetch(_ host: String) async -> NodeStatus? {
        guard let url = URL(string: "http://\(host):\(fePort)/api/field/summary") else { return nil }
        var req = URLRequest(url: url)
        req.timeoutInterval = 4
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let j = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        var s = NodeStatus()
        s.name = j["node"] as? String
        s.ledColour = (j["led"] as? [String: Any])?["color"] as? String
        var p = j["power"] as? [String: Any]
        if let b = p?["battery"] as? [String: Any] { p = b }
        if p?["available"] as? Bool == true { s.battery = p?["battery_percent"] as? Int }
        s.meshNodes = (j["mesh"] as? [String: Any])?["nodes"] as? Int
        return s
    }
}

@main
struct ManetNodeApp: App {
    var body: some Scene {
        WindowGroup { ContentView().preferredColorScheme(.dark).frame(minWidth: 460, minHeight: 420) }
            .windowResizability(.contentMinSize)
    }
}

struct LogoView: View {
    var size: CGFloat
    var body: some View {
        Canvas { ctx, sz in
            let s = sz.width, m = s * 0.12
            let T = CGPoint(x: s / 2, y: m), L = CGPoint(x: m, y: s - m * 1.3), R = CGPoint(x: s - m, y: s - m * 1.3)
            func mid(_ a: CGPoint, _ b: CGPoint) -> CGPoint { CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2) }
            let M1 = mid(T, L), M2 = mid(L, R), M3 = mid(R, T)
            let C = CGPoint(x: (T.x + L.x + R.x) / 3, y: (T.y + L.y + R.y) / 3)
            var web = Path()
            for (a, b) in [(T, M2), (L, M3), (R, M1), (M1, M2), (M2, M3), (M3, M1)] { web.move(to: a); web.addLine(to: b) }
            ctx.stroke(web, with: .color(Palette.cyan.opacity(0.65)), style: StrokeStyle(lineWidth: s * 0.03, lineCap: .round))
            for n in [M1, M2, M3, C] {
                ctx.fill(Path(ellipseIn: CGRect(x: n.x - s * 0.045, y: n.y - s * 0.045, width: s * 0.09, height: s * 0.09)), with: .color(Palette.cyan.opacity(0.65)))
            }
            var tri = Path()
            tri.move(to: T); tri.addLine(to: L); tri.addLine(to: R); tri.closeSubpath()
            ctx.stroke(tri, with: .color(Palette.cyan), style: StrokeStyle(lineWidth: s * 0.06, lineCap: .round, lineJoin: .round))
            for n in [T, L, R] {
                ctx.fill(Path(ellipseIn: CGRect(x: n.x - s * 0.095, y: n.y - s * 0.095, width: s * 0.19, height: s * 0.19)), with: .color(Palette.cyan))
            }
        }
        .frame(width: size, height: size)
    }
}

struct OutlineButton: View {
    var title: String
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(.system(size: 15, weight: .bold)).foregroundColor(Palette.cyan)
                .frame(maxWidth: .infinity, minHeight: 44)
                .overlay(RoundedRectangle(cornerRadius: 2).stroke(Palette.line, lineWidth: 1))
        }
    }
}

struct ContentView: View {
    @StateObject private var store = NodeStore()
    @State private var adding = false
    @State private var newHost = ""
    private let timer = Timer.publish(every: 15, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack {
            Palette.bg.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 12) {
                    LogoView(size: 46)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("ManetNode").font(.system(size: 26, weight: .bold)).foregroundColor(Palette.text)
                        Text("OPENMANET\nFIELD EXTENSION").font(.system(size: 10)).kerning(1.2).foregroundColor(Palette.sub)
                    }
                    Spacer()
                    Button("ADD NODE") { adding = true }
                        .buttonStyle(.plain)
                        .font(.system(size: 13, weight: .bold)).foregroundColor(Palette.cyan)
                        .padding(.horizontal, 12).frame(minHeight: 36)
                        .overlay(RoundedRectangle(cornerRadius: 2).stroke(Palette.line, lineWidth: 1))
                }
                Text("Click a button to open that node's web page in your browser. Right-click a saved node to remove it.")
                    .font(.system(size: 12)).foregroundColor(Palette.sub)
                ScrollView {
                    VStack(spacing: 10) { ForEach(store.hosts, id: \.self) { card($0) } }
                }
            }
            .padding(18)
        }
        .task { await store.refresh() }
        .onReceive(timer) { _ in Task { await store.refresh() } }
        .alert("Add a node", isPresented: $adding) {
            TextField("Node address, e.g. 10.41.0.2", text: $newHost)
            Button("Cancel", role: .cancel) { newHost = "" }
            Button("Add") { store.add(newHost); newHost = "" }
        }
    }

    @ViewBuilder
    private func card(_ host: String) -> some View {
        let entry = store.status[host]
        let checked = entry != nil
        let s = entry ?? nil
        let name = s?.name ?? host
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                Circle().fill(s == nil ? Palette.muted : Palette.led(s?.ledColour)).frame(width: 12, height: 12)
                Text(name).font(.system(size: 18, weight: .bold)).foregroundColor(Palette.text)
                Spacer()
                Text(!checked ? "Checking…" : s != nil ? "System Online" : "Not answering")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(!checked ? Palette.sub : s != nil ? Palette.ok : Palette.bad)
            }
            Text(meta(host, s)).font(.system(size: 12, design: .monospaced)).foregroundColor(Palette.sub)
            HStack(spacing: 10) {
                OutlineButton(title: "OpenManet") { open(host, openManetPort) }.buttonStyle(.plain)
                OutlineButton(title: "FieldExtension") { open(host, fePort) }.buttonStyle(.plain)
            }
        }
        .padding(14)
        .background(Palette.panel)
        .overlay(RoundedRectangle(cornerRadius: 2).stroke(Palette.line, lineWidth: 1))
        .contextMenu {
            if store.saved.contains(host) { Button("Remove \(name)") { store.remove(host) } }
        }
    }

    private func meta(_ host: String, _ s: NodeStatus?) -> String {
        var parts = [host]
        if let b = s?.battery { parts.append("battery \(b)%") }
        if let n = s?.meshNodes { parts.append("\(n) nodes on mesh") }
        return parts.joined(separator: " · ")
    }

    private func open(_ host: String, _ port: Int) {
        if let u = URL(string: "http://\(host):\(port)/") { NSWorkspace.shared.open(u) }
    }
}
