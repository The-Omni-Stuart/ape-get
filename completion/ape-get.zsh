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
        'untrust:remove a repo'"'"'s trusted signing key'
        'update:update one release or --all'
        'upgrade:alias for update'
        'list:list installed releases'
        'info:show details for an installed release'
        'providers:list add update remove or recover provider instances'
        'rollback:restore the latest backup'
        'doctor:diagnose state and heal with --heal'
        'config:show or set the policy profile'
        'adopt:adopt an existing Linux install'
        'lock:lock a release against updates'
        'unlock:unlock a release'
        'about:about ape-get'
        'completions:print a shell completion script'
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
        '-y[assume yes]'
        '--colour[terminal styling]:colour:(auto always never)'
        '--no-progress[never render progress bars]'
        '--experimental-compat[enable the compatibility path]'
        '--help[show help]'
    )
    case $words[CURRENT] in
        -*) compadd -a globals; return ;;
    esac

    # $words[1] is the executable; the command is $words[2]
    case $words[2] in
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
