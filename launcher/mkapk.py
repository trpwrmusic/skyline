import zipfile,sys
src,dex,out=sys.argv[1:4]
zi=zipfile.ZipFile(src); zo=zipfile.ZipFile(out,'w')
for i in zi.infolist():
    n=zipfile.ZipInfo(i.filename,date_time=(2026,1,1,0,0,0)); n.compress_type=i.compress_type
    zo.writestr(n,zi.read(i.filename))
n=zipfile.ZipInfo('classes.dex',date_time=(2026,1,1,0,0,0)); n.compress_type=zipfile.ZIP_DEFLATED
zo.writestr(n,open(dex,'rb').read()); zo.close()
