# Records the README demo with https://github.com/charmbracelet/vhs:
#
# Build with ./gradlew releaseBundle, then put the fresh launcher on PATH:
# On macOS: export PATH="$PWD/build/jpackage/image/jmix.app/Contents/MacOS:$PATH"
# On Linux: export PATH="$PWD/build/jpackage/image/jmix/bin:$PATH"
# Set JAVA_HOME to a development JDK compatible with the demo's Jmix version.
# Capture: mkdir -p build/demo
#          JMIX_DEMO_DIR=$(mktemp -d) vhs docs/demo.tape
# Render: bash docs/render-demo.sh
#
# Stable checkpoints from one complete wizard, including its real progress bar.
# render-demo.sh aligns panels and blends scene transitions.
# Capture inside each pause: a screenshot at its end can select the next frame.
# Creates JMIX_DEMO_DIR/demo and quits the post-generation actions menu.

Output build/demo/raw.gif

Require jmix

Set Shell zsh
Set FontSize 18
Set Width 1000
Set Height 560
Set Padding 20
Set Framerate 25
Set TypingSpeed 90ms
Set CursorBlink false
# Keep secondary text readable when the GIF is scaled down in the README.
Set Theme { "name": "Jmix", "background": "#1e1f29", "foreground": "#f8f8f2", "black": "#1e1f29", "red": "#ff5555", "green": "#50fa7b", "yellow": "#f1fa8c", "blue": "#8be9fd", "magenta": "#ff79c6", "cyan": "#8be9fd", "white": "#f8f8f2", "brightBlack": "#a2a7b5", "brightRed": "#ff5555", "brightGreen": "#50fa7b", "brightYellow": "#f1fa8c", "brightBlue": "#8be9fd", "brightMagenta": "#ff79c6", "brightCyan": "#8be9fd", "brightWhite": "#ffffff", "cursor": "#f8f8f2", "selection": "#44475a" }

Hide
Type@1ms 'cd "$JMIX_DEMO_DIR" && ln -s "$JAVA_HOME" jdk && export JAVA_HOME="$PWD/jdk" && unset NO_COLOR && export FORCE_COLOR=3 && demo_args=(--repository https://global.repo.jmix.io/repository/public --jmix-version 3.0.1 --template application --theme aura --no-git)' Enter
Type@1ms 'jmix --no-update new "${demo_args[@]}"' Enter
Wait+Screen@30s /Enter project name/
Show

# Let the welcome logo establish the beginning.
Sleep 2s
Type "demo"
Sleep 300ms
Screenshot build/demo/welcome.png
Sleep 500ms
Enter

# Show the real wizard history with the main project fields filled in.
Wait+Screen /Enter base package/
Enter
Wait+Screen /Enter project id/
Type "demo"
Sleep 300ms
Screenshot build/demo/project-info.png
Sleep 3s
Enter
Wait+Screen /Select locales/
Down
Space
Sleep 300ms
Screenshot build/demo/localization.png
Sleep 1400ms
Enter
Wait+Screen /Select add-ons/
Sleep 300ms
Screenshot build/demo/addons.png
Sleep 1800ms

# German translation is already selected; add Quartz using search.
Type "/jobs"
Enter
Wait+Screen /Quartz/
Sleep 300ms
Screenshot build/demo/filtered.png
Sleep 700ms
Space
Wait+Screen /\[x\] Quartz/
Sleep 300ms
Screenshot build/demo/selected.png
Sleep 1400ms

# Finish the actual wizard, choosing a project subdirectory.
Enter
Wait+Screen /Select project location/
Down
Sleep 300ms
Screenshot build/demo/location.png
Sleep 2s
Enter
Hide
Wait+Screen@5m /What's next/
Type "q"
Sleep 300ms
Show
Sleep 300ms
Screenshot build/demo/success.png
Sleep 3s
