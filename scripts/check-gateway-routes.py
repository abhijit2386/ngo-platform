import re,glob
root='/home/claude/ngo-platform'
routes=[]  # (order, service, [patterns])
s=open(root+'/services/api-gateway/src/main/java/org/ngoplatform/gateway/Routes.java').read()
for m in re.finditer(r'\.route\("(\w+)",\s*r\s*->\s*r\.order\((\d+)\)\.path\((.*?)\)\s*\.uri\((\w+)\)',s,re.S):
    pats=re.findall(r'"([^"]+)"',m.group(3)); routes.append((int(m.group(2)),m.group(4),pats))
routes.sort(key=lambda x:x[0])
def to_re(p):
    r=re.escape(p).replace(r'/\*\*','(/.*)?').replace(r'\*','[^/]+')
    return re.compile('^'+r+'$')
svcmap={'identity':'identity-service','registry':'ngo-registry-service','subscription':'subscription-service','donor':'donor-service','donation':'donation-payment-service','receipt':'receipt-service','campaign':'campaign-project-service','operations':'ngo-operations-service','helpdesk':'helpdesk-service','notification':'notification-service','reporting':'reporting-service'}
def route_of(path):
    for o,svc,pats in routes:
        for p in pats:
            if to_re(p).match(path): return svcmap[svc]
    return None
bad=0
for f in glob.glob(root+'/services/*/src/main/java/**/*Controller.java',recursive=True):
    svc=f.split('/services/')[1].split('/')[0]
    src=open(f).read()
    base=re.search(r'@RequestMapping\("([^"]*)"\)\s*public class',src)
    base=base.group(1) if base else ''
    for m in re.finditer(r'@(Get|Post|Put|Delete|Patch)Mapping(?:\((?:value\s*=\s*)?"([^"]*)"[^)]*\))?',src):
        path=(base+(m.group(2) or '')).replace('//','/')
        if path.startswith('/internal') or not path: continue
        concrete=re.sub(r'\{[^}]+\}','X',path)
        r=route_of(concrete)
        if r!=svc:
            bad+=1; print(f'{svc:28} {m.group(1).upper():6} {path:60} -> {r}')
print('mismatches:',bad)
