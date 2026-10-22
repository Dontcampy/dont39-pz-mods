import csv, sys, collections
rows=[r for r in csv.DictReader(open(sys.argv[1],encoding='utf-8-sig')) if r['kind']=='file_task' and r['start_ms']]
txt=open(sys.argv[1][:-4]+'.txt',encoding='utf-8').read()
t0=min(float(r['start_ms']) for r in rows); 
for r in rows:
    r['s']=float(r['start_ms'])-t0; r['e']=r['s']+float(r['duration_ms']); r['w']=float(r['worker_ms'] or 0)
    r['t']=r['task_type'].replace('FileTask_','').replace('TileDepthTextures$','Depth')
end=max(r['e'] for r in rows); print('tasks',len(rows),'span %.1f s'%(end/1000))
B=1000
types=sorted(set(r['t'] for r in rows))
print('sec  '+'  '.join('%14s'%t[:14] for t in types)+'   (worker-ms finished in each second)')
for b in range(int(end//B)+1):
    print('%3d  '%b+'  '.join('%14d'%sum(r['w'] for r in rows if r['t']==t and b*B<=r['e']<(b+1)*B) for t in types))
for t in types:
    rs=[r for r in rows if r['t']==t]; 
    print('%-22s n=%4d first sent %.1f s, last done %.1f s, worker %.1f s, sent->done sum %.1f s'%(t,len(rs),min(r['s'] for r in rs)/1000,max(r['e'] for r in rs)/1000,sum(r['w'] for r in rs)/1000,sum(r['e']-r['s'] for r in rs)/1000))
d=[r for r in rows if r['t']=='DepthLoadTask']
mods=collections.Counter(r['mod'] or 'game' for r in d); print('depth by mod', mods.most_common(8))
