#!/usr/bin/env bash
# Completion assertions: source the generated bash script against a stub
# ape-get on PATH, feed COMP_WORDS to _ape_get, assert COMPREPLY.
# usage: test_completions.sh <ape-get-exe> <workdir> <srcdir>
set -u
exe="$1" work="$2" srcdir="$3"
rm -rf "$work" && mkdir -p "$work" || exit 1

cat > "$work/ape-get" <<'STUB'
#!/bin/sh
case "$*" in
'list --json')
    echo '{"apps":[{"provider":"github","repo":"neovim/neovim","version":"v0.12.6"},{"provider":"gitlab","repo":"group/tool","version":"1.0"},{"provider":"self","repo":"ape-get","version":"2.0.0"}]}' ;;
'providers list --json')
    echo '{"providers":[{"name":"codeberg","type":"gitea"},{"name":"github","type":"github"},{"name":"gitlab","type":"gitlab"}]}' ;;
*) exit 0 ;;
esac
STUB
chmod +x "$work/ape-get"

"$exe" completions bash > "$work/comp.sh" || { echo "completions bash failed"; exit 1; }
"$exe" completions zsh > "$work/comp.zsh" || { echo "completions zsh failed"; exit 1; }

fail=0
note() { echo "FAIL: $*"; fail=1; }

bash -n "$work/comp.sh" || note "bash script does not parse"
bash -n "$work/comp.zsh" || note "zsh script does not parse"
if grep -q -- '-o default' "$work/comp.sh"; then
    note "script may leak filename completion (-o default)"
fi
grep -q 'complete -r ape-get' "$work/comp.sh" ||
    note "script cannot re-register after re-source (no complete -r)"
grep -q "show-all-if-ambiguous" "$work/comp.sh" ||
    note "bash script leaves ties invisible on the first Tab"
if grep -q "bind 'set" "$work/comp.zsh"; then
    note "zsh script contains a bash-only bind"
fi
grep -q bashcompinit "$work/comp.zsh" || note "zsh script lacks the bashcompinit hint"
if grep -q 'completions zsh' "$work/comp.sh"; then
    note "bash script tells users to enable the zsh script"
fi

# parser flags with no help line would silently vanish from completions;
# --color is the sanctioned exception: the US alias is hidden by design
while IFS= read -r tok; do
    grep -qF -- "$tok" "$work/comp.sh" || note "flag $tok missing from completions"
done < <(grep -oE '"--?[a-z][a-z-]*"' "$srcdir/src/main/cmd.c" |
         sort -u | tr -d '"' | grep -v '^--color$')

export PATH="$work:$PATH"
. "$work/comp.sh"

check() { # check <label> <want-token|NONE> <words... ; last word is "to be completed">
    local label="$1" want="$2"; shift 2
    COMP_WORDS=("$@")
    COMP_CWORD=$(( ${#COMP_WORDS[@]} - 1 ))
    COMPREPLY=()
    _ape_get
    local joined=" ${COMPREPLY[*]-} "
    if [ "$want" = "NONE" ]; then
        [ ${#COMPREPLY[@]} -eq 0 ] ||
            note "$label: want no completions, got '${COMPREPLY[*]}'"
    else
        case "$joined" in
        *" $want "*) ;;
        *) note "$label: want '$want' among '${COMPREPLY[*]}'" ;;
        esac
    fi
}

check "command i"          install              ape-get i
check "command i again"    info                 ape-get i
check "global at start"    --json               ape-get --j
check "list has no version" NONE                ape-get list --v
check "install --v"        --version            ape-get install --v
check "uninstall ref"      github:neovim/neovim ape-get uninstall ""
check "uninstall ref two"  gitlab:group/tool    ape-get uninstall ""
check "update ref"         self:ape-get         ape-get update ""
check "install prefix"     github:              ape-get install gi
check "help subcommand"    uninstall            ape-get help un
check "providers verbs"    recover              ape-get providers ""
check "provider name"      gitlab               ape-get providers update g
check "colour value"       always               ape-get --colour ""
check "provider value"     gitlab               ape-get install --provider g
check "completions arg"    bash                 ape-get completions b
check "flag after value"   --desktop            ape-get install --version 1.2.3 --d

exit "$fail"
