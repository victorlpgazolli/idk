class Idk < Formula
  desc "Interactive Debug Kit (idk) - TUI debugger for Android apps"
  homepage "https://github.com/victorgazolli/idk"
  url "https://github.com/victorgazolli/idk/releases/download/vVERSION/idk-macos-arm64.zip"
  version "VERSION"
  sha256 "SHA256"
  license "MIT"

  depends_on "tmux"
  depends_on :macos
  depends_on arch: :arm64

  def install
    # Install binaries to libexec
    libexec.install "idk"
    libexec.install "idk-bridge"
    
    # Create a wrapper script in bin that sets the environment variable and calls the real binary
    (bin/"idk").write_env_script libexec/"idk", IDK_BRIDGE_PATH: libexec/"idk-bridge"
  end

  def caveats
    <<~EOS
      idk requires tmux to be installed and accessible in your PATH.
      It also requires adb (Android Debug Bridge) to interact with devices.
      You can install adb via:
        brew install --cask android-platform-tools
    EOS
  end

  test do
    # Verify the binary is present
    assert_predicate libexec/"idk", :exist?
    assert_predicate libexec/"idk-bridge", :exist?
  end
end
