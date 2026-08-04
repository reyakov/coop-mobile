import SwiftUI

private enum OnboardingRoute: Hashable {
    case newIdentity
    case importIdentity
}

struct OnboardingView: View {
    @State private var path: [OnboardingRoute] = []

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 32) {
                Spacer()

                Image(systemName: "bubble.left.and.bubble.right.fill")
                    .font(.system(size: 80))
                    .foregroundStyle(.tint)

                VStack(spacing: 8) {
                    Text("Coop")
                        .font(.largeTitle.bold())
                    Text("Simple, fast, and reliable nostr messaging")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }

                Spacer()

                VStack(spacing: 12) {
                    Button {
                        path.append(.newIdentity)
                    } label: {
                        Text("Start Messaging")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)

                    Button {
                        path.append(.importIdentity)
                    } label: {
                        Text("Add an Existing Identity")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                }
                .padding(.horizontal)

                Text("By continuing, you agree to our Terms of Service and Privacy Policy")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
            }
            .padding()
            .navigationDestination(for: OnboardingRoute.self) { route in
                switch route {
                case .newIdentity:
                    NewIdentityView()
                case .importIdentity:
                    ImportView()
                }
            }
        }
    }
}
