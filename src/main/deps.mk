# Module graph. Format: module:dep1,dep2,... (empty after ':'
# = leaf). Levels are documentation; the CI check derives them from these
# edges and rejects cycles or edges that do not point strictly downward.
json:
repo:
cmd:
ui:
path:
semver:
redact:
clock:
crypto:
fs:path
proc:path
output:ui,redact
prompt:ui
platform:fs,proc,path
compat:fs,proc,path
http:crypto,fs,proc,redact,clock
txn:fs,json,path,clock
keys:crypto,fs,json,path
provider:repo,json,http,path,proc
state:json,repo,platform,txn,fs,path,clock
select:platform,path,repo
verify:crypto,keys,fs,proc,path
probe:provider,repo,clock,http
doctor:state,platform,fs,proc,path,txn,compat,prompt
pipeline:json,repo,cmd,ui,path,semver,redact,clock,crypto,fs,proc,output,prompt,platform,compat,http,txn,keys,provider,state,select,verify,probe,doctor
main:json,repo,cmd,ui,path,semver,redact,clock,crypto,fs,proc,output,prompt,platform,compat,http,txn,keys,provider,state,select,verify,probe,doctor,pipeline
