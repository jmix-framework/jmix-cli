# Records the README demo with https://github.com/charmbracelet/vhs:
#
#   ./gradlew releaseBundle                     # build the image the demo runs
#   JMIX_DEMO_DIR=$(mktemp -d) vhs docs/demo.tape
#
# The `jmix` command must be on PATH, and JMIX_DEMO_DIR is the scratch
# directory the wizard generates into. Set JMIX_CLI_NO_AUTO_UPDATE=1 so the
# recording never shows an update check.

Output docs/demo.gif

Require jmix

Set Shell zsh
Set FontSize 16
Set Width 1000
Set Height 460
Set Padding 20
Set Framerate 24
Set TypingSpeed 45ms
Set Theme "TokyoNight"

# Setup is hidden: the demo should open on a clean prompt.
Hide
Type "cd $JMIX_DEMO_DIR && clear" Enter
Sleep 500ms
Show

Type "jmix new" Sleep 300ms Enter

# The banner, held long enough to read, then the first question.
Sleep 2800ms

Type "onboarding" Sleep 400ms Enter
Sleep 1400ms

# Accept the offered repository and Jmix version.
Enter Sleep 1200ms
Enter Sleep 1800ms

# Rest on the template list, then stop before generation.
Sleep 1200ms
Ctrl+C
Sleep 600ms
