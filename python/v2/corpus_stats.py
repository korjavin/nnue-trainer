import json, collections, glob, os
d=os.path.dirname
import sys
base=sys.argv[1] if len(sys.argv)>1 else "python/v2/corpus/corpus.jsonl"
per=collections.defaultdict(lambda:{"win":0,"draw":0,"loss":0})
tot={"win":0,"draw":0,"loss":0}
n=0
with open(base) as f:
    for line in f:
        line=line.strip()
        if not line: continue
        o=json.loads(line); n+=1
        sz=f"{o['rows']}x{o['cols']}"
        w=o["wdl"]
        k="draw" if abs(w-0.5)<1e-6 else ("win" if w>0.5 else "loss")
        per[sz][k]+=1; tot[k]+=1
print("total positions:", n)
print("overall draw-rate: %.1f%%" % (100*tot['draw']/n))
print("overall win/draw/loss: %d/%d/%d" % (tot['win'],tot['draw'],tot['loss']))
for sz in sorted(per):
    c=per[sz]; s=sum(c.values())
    print("  %-6s n=%-5d win=%d(%.0f%%) draw=%d(%.0f%%) loss=%d(%.0f%%)" % (
        sz,s,c['win'],100*c['win']/s,c['draw'],100*c['draw']/s,c['loss'],100*c['loss']/s))
