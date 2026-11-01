#compdef ape-get ape-get.com
# zsh completion for ape-get (C-only UX)
# install: drop in a $fpath dir as _ape-get, or source it
_ape_get()
{
    local -a commands
    commands=(
        'search:search for a release by name'
        'install:install provider:owner/repo or owner/repo'
        'uninstall:uninstall an installed release'
        'update:update one release or --all'
        'list:list installed releases'
        'info:show details for an installed release'
        'providers:list add update remove or recover providers'
        'rollback:restore the latest backup'
        'doctor:diagnose state and heal with --heal'
        'adopt:adopt an existing Linux install'
        'lock:lock a release against updates'
        'unlock:unlock a release'
        'about:about ape-get'
        'help:help for a command'
    )

    if ((CURRENT == 2 )); then
        _describe -t commands 'ape-get command' commands
        return
    fi

    local -a globals
    globals=(
        '--json[machine-readable output]'
        '--quiet[suppress progress]'
        '--yes[assume yes]'
        '--colour[terminal styling]:colour:(auto always never)'
        '--no-progress[never render progress bars]'
        '--help[show help]'
    )
    case $words[CURRENT] in
        -*) compadd -a globals; return ;;
    esac

    case $words[1] in
        providers)
            if ((CURRENT == 3 )); then
                local -a subs=(list show add update remove recover)
                _describe -t subs 'providers subcommand' subs
            fi ;;
        help)
            if ((CURRENT == 3 )); then
                _describe -t commands 'ape-get command' commands
            fi ;;
        doctor)
            _values 'doctor flags' \
                '--diagnose[report only]' '--heal[report then repair]' \
                '--json' '--quiet' '--colour[styling]:colour:(auto always never)'
            return ;;
    esac
    _arguments -S $globals
}
_ape_get "$@"
