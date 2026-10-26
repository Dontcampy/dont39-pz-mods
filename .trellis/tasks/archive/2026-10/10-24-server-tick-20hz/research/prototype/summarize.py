import re,sys,glob,os,statistics as st
rows=[]
for f in sorted(glob.glob(os.path.join(sys.argv[1],"hz*.txt"))):
    if f.endswith("-stop.txt"): continue
    txt=open(f).read(); lab=os.path.basename(f)[:-4]
    pc=re.search(r"proc_cpu_cores=([\d.]+)",txt)
    W=[dict(re.findall(r"(\w+)=([-\d.]+)",l.replace("work_ms mean","work_mean").replace("gap_ms mean","gap_mean"))) for l in txt.splitlines() if "probe hz" in l]
    if not W: continue
    g=lambda k:[float(w[k]) for w in W]
    # p50/p95/max come after work_mean in order: parse explicitly
    wp=[re.search(r"work_ms mean=([\d.]+) p50=([\d.]+) p95=([\d.]+) max=([\d.]+)",l).groups() for l in txt.splitlines() if "probe hz" in l]
    gp=[re.search(r"gap_ms mean=([\d.]+) max=([\d.]+)",l).groups() for l in txt.splitlines() if "probe hz" in l]
    m=st.mean
    rows.append((lab,len(W),m(g("tps")),m(float(x[0]) for x in wp),m(float(x[1]) for x in wp),m(float(x[2]) for x in wp),max(float(x[3]) for x in wp),max(float(x[1]) for x in gp),
      m(g("cpu_proc")),float(pc.group(1)) if pc else -1,m(g("cpu_main")),m(g("sent_Bps"))/1024,m(g("players")),m(g("zombies")),m(g("vehicles")),m(g("mult")),m(a*b for a,b in zip(g("mult"),g("tps")))))
hdr="run win tps work_mean work_p50 work_p95(avg) work_max gap_max cpu_proc(jmx) cpu_proc(/proc) cpu_main sent_KiBps players zombies vehicles mult mult*tps".split()
print("| "+" | ".join(hdr)+" |"); print("|"+"---|"*len(hdr))
for r in rows: print("| "+" | ".join(r[0:1]+tuple(f"{v:.2f}" if isinstance(v,float) else str(v) for v in r[1:]))+" |")
