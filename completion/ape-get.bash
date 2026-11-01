# bash completion for ape-get (C-only UX)
# install:  complete -F _ape_get ape-get   (or source this file)
_ape_get()
{
    local commands="search install uninstall update list info providers rollback doctor adopt lock unlock help about"
    local globals="--json --quiet --yes -y --colour --color --no-progress --help"
    local cur prev
    cur="${COMP_WORDS[COMP_CWORD]}"
    prev="${COMP_WORDS[COMP_CWORD-1]}"

    case "$prev" in
        --colour|--color)
            COMPREPLY=( $(compgen -W "auto always never" -- "$cur") )
            return ;;
        --provider)
            COMPREPLY=( $(compgen -W "codeberg github gitlab" -- "$cur") )
            return ;;
        --kind)
            COMPREPLY=( $(compgen -W "appimage portable-exe ape nsis msi dmg" -- "$cur") )
            return ;;
        --channel)
            COMPREPLY=( $(compgen -W "stable beta" -- "$cur") )
            return ;;
        --asset|--version|--name|--icon|--api|--token-env)
            return ;;
    esac

    if [[ "$cur" == -* ]]; then
        COMPREPLY=( $(compgen -W "$globals" -- "$cur") )
    elif [[ $COMP_CWORD -eq 1 ]]; then
        COMPREPLY=( $(compgen -W "$commands" -- "$cur") )
    else
        case "${COMP_WORDS[1]}" in
            providers)
                if [[ $COMP_CWORD -eq 2 ]]; then
                    COMPREPLY=( $(compgen -W "list show add update remove recover" -- "$cur") )
                fi ;;
            help)
                if [[ $COMP_CWORD -eq 2 ]]; then
                    COMPREPLY=( $(compgen -W "$commands" -- "$cur") )
                fi ;;
        esac
    fi
}
complete -F _ape_get ape-get ape-get.com
