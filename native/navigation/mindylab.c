/* Temporary native demo for EXACT installed G2 1.0.1.202609052141/build 2018.
 * Function pointers and ioctl layouts are traced from that image's pngplayer.
 * Persistent launcher adds one idle worker; no firmware writer, audio or network listener.
 */
#include <stdint.h>
#include <stddef.h>

#define FN(type, address) ((type)((uintptr_t)(address) | 1u))
#define os_open FN(int (*)(const char *, int, ...), 0x106cd0f0)
#define os_close FN(int (*)(int), 0x106cb050)
#define os_read FN(int (*)(int, void *, unsigned), 0x106cd480)
#define os_write FN(int (*)(int, const void *, unsigned), 0x106cbb60)
#define os_ioctl FN(int (*)(int, int, void *), 0x106cdd00)
#define os_mmap FN(void *(*)(void *, unsigned, int, int, int, int64_t), 0x106cc9d0)
#define os_munmap FN(int (*)(void *, unsigned), 0x106cca90)
#define os_malloc FN(void *(*)(unsigned), 0x106cbef8)
#define os_free FN(void (*)(void *), 0x106cd548)
#define os_sleep FN(int (*)(unsigned), 0x106cd520)
static int mobile_pid(void);
#define os_pid mobile_pid
#define os_unlink FN(int (*)(const char *), 0x106cbac0)
#define os_printf FN(int (*)(const char *, ...), 0x106cd5c8)
#define os_errno FN(int *(*)(void), 0x106cd580)

enum { O_RD=1, O_WR=2, O_RW=3, O_CREAT=4, O_EXCL=8, O_TRUNC=32, O_NONBLOCK=64 };
enum { GET_VIDEO=0x2801, GET_PLANE=0x2802, GET_POWER=0x2815, PAN=0x2818, PAN_CLEAR=0x2819, BUTTONS_SUPPORTED=0x1c01 };
static const char lock_path[]="/data2/pngplayer.lock";
static int mobile_tick(void);

struct video { uint8_t fmt,pad; uint16_t width,height; uint8_t planes,overlays; };
struct plane { void *memory; uint32_t length; uint16_t stride; uint8_t display,bpp;
               uint32_t virtual_width,virtual_height,xoffset,yoffset; };
_Static_assert(sizeof(struct video)==8,"Installed video layout");
_Static_assert(sizeof(struct plane)==28,"Installed plane layout");
struct state { uint32_t magic,phase,error,width,height,stride,bytes,bpp,supported,
                       inputs,last_buttons,ticks,restored,lock_removed,pan_error,
                       pan_errno,pan_clears,buffer_restored,gestures,raw_changes,power; };
volatile struct state mindy_state={.magic=0x4d494e44};
static uint8_t *pixels __attribute__((section(".data")));
static unsigned width __attribute__((section(".data")));
static unsigned height __attribute__((section(".data")));
static unsigned stride __attribute__((section(".data")));
static unsigned bpp __attribute__((section(".data")));

/* Avoid libc imports, including compiler-generated memory helpers. */
__attribute__((noinline)) void *memset(void *p,int c,size_t n) { uint8_t *d=p; while(n--) *d++=(uint8_t)c; return p; }
__attribute__((noinline)) void *memcpy(void *p,const void *s,size_t n) { uint8_t *d=p; const uint8_t *a=s; while(n--) *d++=*a++; return p; }
void __aeabi_memclr(void *p,size_t n) { memset(p,0,n); }
void __aeabi_memclr4(void *p,size_t n) { memset(p,0,n); }
void __aeabi_memcpy(void *p,const void *s,size_t n) { memcpy(p,s,n); }
void __aeabi_memcpy4(void *p,const void *s,size_t n) { memcpy(p,s,n); }
static unsigned decimal(char *p,unsigned n) {
    char reverse[12]; unsigned count=0; do { reverse[count++]=(char)('0'+n%10);n/=10; } while(n);
    for(unsigned i=0;i<count;i++)p[i]=reverse[count-1-i];return count;
}
static int same(const char *a,const char *b,unsigned n) { for(unsigned i=0;i<n;i++)if(a[i]!=b[i])return 0;return 1; }
static int write_all(int fd,const void *data,unsigned count) {
    const uint8_t *p=data;while(count){int n=os_write(fd,p,count);if(n<=0||(unsigned)n>count)return -1;p+=n;count-=(unsigned)n;}return 0;
}
static int own_lock(const char *pid,unsigned n) {
    char data[32];int fd=os_open(lock_path,O_RD);if(fd<0)return 0;
    int got=os_read(fd,data,sizeof(data));os_close(fd);return got==(int)n&&same(data,pid,n);
}
static int query(int fd,struct video *v,struct plane *p) {
    memset(v,0,sizeof(*v));memset(p,0,sizeof(*p));
    if(os_ioctl(fd,GET_VIDEO,v)<0||os_ioctl(fd,GET_PLANE,p)<0)return -1;
    mindy_state.width=v->width;mindy_state.height=v->height;mindy_state.stride=p->stride;
    mindy_state.bytes=p->length;mindy_state.bpp=p->bpp;
    os_printf("MINDYLAB display %ux%u fmt=%u bpp=%u stride=%u bytes=%u offsets=%u,%u\n",
              v->width,v->height,v->fmt,p->bpp,p->stride,p->length,p->xoffset,p->yoffset);
    if(v->width<320||v->width>1024||v->height<200||v->height>768)return -2;
    if(!((v->fmt==11&&p->bpp==16)||(v->fmt==12&&p->bpp==24)))return -3;
    if(p->stride<(unsigned)v->width*(p->bpp/8)||p->length<(unsigned)p->stride*v->height||p->length>4*1024*1024)return -4;
    return 0;
}
/* Installed fb.c: ioctl 0x2818 queues a pan; 0x2819/-1 clears that plane's
 * transient queue. If it fills while normal rendering is paused, retain only
 * our latest frame. Never clear another application's queue without our lock. */
static int present(int fd,struct plane *p) {
    if(os_ioctl(fd,PAN,p)>=0)return 0;
    int error=*os_errno();mindy_state.pan_errno=(unsigned)error;mindy_state.pan_error++;
    if(error!=28)return -1;
    if(os_ioctl(fd,PAN_CLEAR,(void *)(intptr_t)-1)<0)return -1;
    mindy_state.pan_clears++;
    return os_ioctl(fd,PAN,p);
}

/* Original compact 5x7 glyphs. Columns, least significant bit at the top. */
static const uint8_t letters[26][5]={
 {126,9,9,9,126},{127,73,73,73,54},{62,65,65,65,34},{127,65,65,34,28},
 {127,73,73,73,65},{127,9,9,9,1},{62,65,73,73,122},{127,8,8,8,127},
 {65,65,127,65,65},{32,64,64,63,1},{127,8,20,34,65},{127,64,64,64,64},
 {127,2,12,2,127},{127,4,8,16,127},{62,65,65,65,62},{127,9,9,9,6},
 {62,65,81,33,94},{127,9,25,41,70},{38,73,73,73,50},{1,1,127,1,1},
 {63,64,64,64,63},{31,32,64,32,31},{127,32,24,32,127},{99,20,8,20,99},
 {3,4,120,4,3},{97,81,73,69,67}};
static const uint8_t digits[10][5]={
 {62,65,73,65,62},{0,66,127,64,0},{98,81,73,73,70},{34,65,73,73,54},
 {24,20,18,127,16},{39,69,69,69,57},{62,73,73,73,48},{1,113,9,5,3},
 {54,73,73,73,54},{6,73,73,73,62}};
static void pixel(unsigned x,unsigned y,unsigned on) {
    if(x>=width||y>=height)return;
    unsigned pos=y*stride+x*(bpp/8);
    pixels[pos]=on?255:0;pixels[pos+1]=on?255:0;if(bpp==24)pixels[pos+2]=on?255:0;
}
static void box(unsigned x,unsigned y,unsigned w,unsigned h,unsigned on) {
    if(x>=width||y>=height)return;if(w>width-x)w=width-x;if(h>height-y)h=height-y;
    for(unsigned j=0;j<h;j++)for(unsigned i=0;i<w;i++)pixel(x+i,y+j,on);
}
static void text(const char *s,unsigned y,unsigned scale) {
    unsigned n=0;while(s[n])n++;unsigned extent=(n*6-1)*scale;
    if(!n||extent>width)return;unsigned x=(width-extent)/2;
    while(*s){const uint8_t *glyph=0;char c=*s++;if(c>='A'&&c<='Z')glyph=letters[c-'A'];
        else if(c>='0'&&c<='9')glyph=digits[c-'0'];
        static const uint8_t dot[]={0,96,96,0,0},dash[]={8,8,8,8,8},question[]={2,1,81,9,6},slash[]={32,16,8,4,2};
        if(c=='.'||c==',')glyph=dot;else if(c=='-')glyph=dash;else if(c=='?')glyph=question;else if(c=='/')glyph=slash;
        if(glyph)for(unsigned col=0;col<5;col++)for(unsigned row=0;row<7;row++)if(glyph[col]&(1u<<row))box(x+col*scale,y+row*scale,scale,scale,1);
        x+=6*scale;}
}
static void demo_draw(unsigned count,unsigned seconds) {
    memset(pixels,0,stride*height);
    box(20,20,width-40,2,1);box(20,height-22,width-40,2,1);
    box(20,20,2,height-40,1);box(width-22,20,2,height-40,1);
    text("NATIVE APP",height/8,2);
    text("MINDYLAB",height/3,width>=600?8:6);
    char message[32]="INPUTS ";unsigned n=decimal(message+7,count);message[7+n]=0;
    text(message,height*3/5,3);
    text("TAP A BUTTON",height*3/4,2);
    char remaining[32]="EXIT IN ";n=decimal(remaining+8,seconds);remaining[8+n]=0;
    text(remaining,height*7/8,1);
    unsigned x=40+(count%5)*((width-100)/5);box(x,height/2,20,12,1);
}

/* Three fixed RAM files. Navigation packets are data, never executable code.
 * Per-boot nonce, monotonic sequence, exact length and checksum, printable
 * bounded fields, no paths/addresses. FTP provides account authentication.
 */
static unsigned nav_mode __attribute__((section(".data")))=0;
struct nav_state {uint32_t sequence,link_age,state,maneuver,fresh,accepted,invalid,layout,awake_requests;};
volatile struct nav_state nav_state __attribute__((section(".data")))={0};
static unsigned char nav_packet[384] __attribute__((section(".data")))={0};
static uint32_t get32(const unsigned char *);
static uint32_t checkbytes(const unsigned char *,unsigned);
static const unsigned char *session_nonce(void);
static int nav_string(const unsigned char *p,unsigned n){
    unsigned end=0;
    for(unsigned i=0;i<n;i++){if(!p[i])end=1;else if(end||p[i]<32||p[i]>126)return 0;}
    return end;
}
unsigned nav_accept(const unsigned char *p,unsigned n){
    if(n!=384||get32(p)!=0x3156414e||get32(p+4)!=2||get32(p+380)!=checkbytes(p,380))return 0;
    unsigned seq=get32(p+8),state=get32(p+12),maneuver=get32(p+32),fresh=get32(p+36);
    if(!seq||seq<=nav_state.sequence||seq>1000000||state>4||maneuver>6||fresh>120||
       !same((const char*)p+16,(const char*)session_nonce(),16)||get32(p+376)>2)return 0;
    if((state==1&&!fresh)||(state!=1&&(maneuver||fresh)))return 0;
    if(!nav_string(p+40,24)||!nav_string(p+64,24)||!nav_string(p+88,128)||!nav_string(p+216,160))return 0;
    memcpy(nav_packet,p,384);nav_state.sequence=seq;nav_state.link_age=0;
    nav_state.state=state;nav_state.maneuver=maneuver;nav_state.fresh=fresh;nav_state.layout=get32(p+376);nav_state.accepted++;return 1;
}
/* Exact installed brn_pm_handle_display(true). It queues on the stock business
 * event loop when called from this worker. In state 1 with LCD already on it
 * only refreshes the existing configured idle timer. No NV writes or persistent
 * timeout changes. Never wake off-head/asleep/power-button state from this hook.
 * After Stop/lease expiry refresh ceases; the normal timer expires naturally. */
static void nav_awake_tick(void){
    static const unsigned char expected[]={0x2d,0xe9,0xf0,0x41,0x86,0xb0,0x04,0x46,0x06,0xaf,0x00,0x20,0x76,0x46};
    if(!nav_mode||mindy_state.phase!=4||nav_state.link_age>=15||
       *(volatile unsigned char*)0x18243146!=1||!same((const char*)0x1067d56c,(const char*)expected,sizeof(expected)))return;
    if(!FN(int (*)(void),0x1067a170)())return;
    FN(void (*)(int),0x1067d56c)(1);nav_state.awake_requests++;
}
static void nav_tick(void){
    if(nav_state.link_age<0x7fffffffu)nav_state.link_age++;
    if(nav_state.fresh)nav_state.fresh--;
    unsigned char b[385];unsigned n=0;int fd=os_open("/tmp/mindy-nav.route",O_RD);
    if(fd>=0){while(n<sizeof(b)){int r=os_read(fd,b+n,sizeof(b)-n);if(r<=0)break;if((unsigned)r>sizeof(b)-n){n=0;break;}n+=(unsigned)r;}
        if(os_close(fd)==0&&n)nav_accept(b,n);}
}
static void wrapped(const char *s,unsigned y,unsigned scale,unsigned lines){
    unsigned cols=(width-60)/(6*scale);if(cols>55)cols=55;
    for(unsigned row=0;row<lines&&*s;row++){
        while(*s==' ')s++;unsigned n=0,last=0;while(s[n]&&n<cols){if(s[n]==' ')last=n;n++;}
        if(s[n]&&last)n=last;char line[56];memcpy(line,s,n);line[n]=0;text(line,y+row*(9*scale),scale);s+=n;
    }
}
static void arrow_at(unsigned type,unsigned x,unsigned y){
    /* Large block arrows. Unknown instructions deliberately have no arrow. */
    if(type==1||type==2){unsigned left=type==1;box(x-52,y-10,104,20,1);box(left?x+32:x-52,y,20,65,1);
        for(unsigned i=0;i<42;i++)box(left?x-85+i:x+43+i,left?y-i+1:y-40+i,2,left?i*2+1:83-i*2,1);
    }else if(type==3){box(x-10,y-30,20,105,1);for(unsigned i=0;i<42;i++)box(x-i,y-66+i,i*2+1,2,1);
    }else if(type==4){box(x+35,y-25,18,100,1);box(x-50,y-45,103,18,1);box(x-50,y-40,18,60,1);
        for(unsigned i=0;i<35;i++)box(x-75+i,y+15+i,70-2*i,2,1);
    }else if(type==5){box(x-50,y-45,100,15,1);box(x-50,y+40,100,15,1);box(x-50,y-30,15,70,1);box(x+35,y-30,15,70,1);
    }else if(type==6){for(unsigned i=0;i<35;i++){box(x-50+i,y+i,6,6,1);box(x-15+i,y+35-i*2,6,6,1);}}
}
static void compact_text(const char *s,unsigned y,unsigned scale){
    unsigned n=0;while(s[n]&&n<26)n++;unsigned extent=(n*6-1)*scale;
    if(!n||extent>320)return;unsigned x=(nav_state.layout==2?width-336:8)+(320-extent)/2;
    for(unsigned i=0;i<n;i++){char c=s[i];const unsigned char *g=0;
        if(c>='A'&&c<='Z')g=letters[c-'A'];else if(c>='0'&&c<='9')g=digits[c-'0'];
        static const uint8_t dot[]={0,96,96,0,0},dash[]={8,8,8,8,8};if(c=='.'||c==',')g=dot;else if(c=='-')g=dash;
        if(g)for(unsigned col=0;col<5;col++)for(unsigned row=0;row<7;row++)if(g[col]&(1u<<row))box(x+col*scale,y+row*scale,scale,scale,1);
        x+=6*scale;}
}
static const char *nav_wait_text(void){return nav_state.link_age>=15?"PHONE LINK LOST":nav_state.state==1||nav_state.state==2?"DIRECTIONS STALE":nav_state.state==3?"ROUTE ENDED":nav_state.state==4?"ALLOW NOTIFICATIONS":"WAITING FOR ROUTE";}
static void draw(unsigned count,unsigned seconds){
    if(!nav_mode){demo_draw(count,seconds);return;}
    memset(pixels,0,stride*height);
    unsigned current=nav_state.state==1&&nav_state.fresh&&nav_state.link_age<15;
    static const char *const labels[]={"DIRECTIONS","LEFT","RIGHT","STRAIGHT","U TURN","ROUNDABOUT","ARRIVED"};
    if(nav_state.layout){
        compact_text((const char*)nav_packet+40,10,2);
        if(current){arrow_at(nav_state.maneuver,nav_state.layout==2?width-176:168,96);
            const char *dist=(const char*)nav_packet+64;compact_text(*dist?dist:labels[nav_state.maneuver],180,*dist?4:2);
            compact_text(labels[nav_state.maneuver],222,2);
            const char *s=(const char*)nav_packet+88;
            for(unsigned row=0;row<3&&*s;row++){while(*s==' ')s++;unsigned n=0,last=0;while(s[n]&&n<26){if(s[n]==' ')last=n;n++;}
                if(s[n]&&last)n=last;char line[27];memcpy(line,s,n);line[n]=0;compact_text(line,250+row*20,2);s+=n;}
        }else compact_text(nav_wait_text(),120,2);
        return;
    }
    text("MINDYLAB NAV",22,3);box(30,58,width-60,2,1);
    text((const char*)nav_packet+40,76,2);
    if(current){
        arrow_at(nav_state.maneuver,width/2,height*27/100);text(labels[nav_state.maneuver],height*42/100,3);
        const char *dist=(const char*)nav_packet+64;text(*dist?dist:"SEE INSTRUCTION",height*50/100,*dist?5:2);
        wrapped((const char*)nav_packet+88,height*61/100,3,3);
        wrapped((const char*)nav_packet+216,height*76/100,2,4);
    }else{
        const char *s=nav_wait_text();
        text(s,height/3,3);wrapped("CHECK GOOGLE MAPS OR WAZE ON YOUR PHONE",height/2,3,3);
    }
    text("STOP ON PHONE TO RETURN",height-40,2);
}

/* Installed 1.0.1/build2018 input callback, verified from local instructions.
 * Ordinary completed taps belong to this screen. Wear, power/long holds and
 * unknown events retain the original callback and exact event/userdata ABI.
 * No device/driver configuration or persistent-setting write.
 *
 * Once installed even once, this module MUST remain resident until reboot:
 * another task could have fetched its callback before we detach it. A finite
 * quiet period is not proof that unloading the code is safe.
 */
typedef void (*input_callback)(const void *, void *);
#define INPUT_SLOT ((volatile uint32_t *)0x181db8d4u)
#define STOCK_INPUT 0x106104a5u
struct input_state {
    uint32_t magic,armed,ever_installed,suppressed,forwarded,last_type,
             acquired,restored,error,quiet_ms,drain_timeout;
};
volatile struct input_state mindy_input_state={.magic=0x494e5054};

__attribute__((noinline)) void mindy_input_callback(const void *event,void *userdata) {
    unsigned type=event?*(const uint8_t *)event:256u;
    mindy_input_state.last_type=type;
    /* Also consume a tap from a dispatcher that fetched our pointer just
     * before detach. Do not forward it into the newly visible stock screen. */
    if(type==7||type==8||type==10||type==11){
        __atomic_fetch_add(&mindy_input_state.suppressed,1u,__ATOMIC_RELAXED);
        return;
    }
    __atomic_fetch_add(&mindy_input_state.forwarded,1u,__ATOMIC_RELAXED);
    ((input_callback)STOCK_INPUT)(event,userdata);
}
static unsigned input_ours(void) {
    return INPUT_SLOT[0]==(uintptr_t)mindy_input_callback&&INPUT_SLOT[1]==0;
}
static int input_swap(uint32_t expected,uint32_t replacement) {
    /* ARMv7-M-compatible exclusives plus explicit barriers also execute in
     * our instruction emulator (which does not implement ARMv8-M LDAEX). */
    __asm__ volatile("dmb sy" ::: "memory");
    int ok=__atomic_compare_exchange_n(INPUT_SLOT,&expected,replacement,0,
                                      __ATOMIC_RELAXED,__ATOMIC_RELAXED);
    __asm__ volatile("dmb sy" ::: "memory");
    return ok;
}
__attribute__((noinline)) int mindy_input_acquire(void) {
    static const uint32_t head[]={0xe92d2300,0xb08e47f0,0xe9c7af08,0xf2413301};
    static const uint32_t dispatch[]={0xb089b5f0,0x46044e11,0x460d6833,0xb1cbaf04};
    mindy_input_state.error=0;mindy_input_state.restored=0;
    mindy_input_state.quiet_ms=0;mindy_input_state.drain_timeout=0;
    for(unsigned i=0;i<4;i++){
        if(((const volatile uint32_t *)0x106104a4u)[i]!=head[i]||
           ((const volatile uint32_t *)0x1061297cu)[i]!=dispatch[i]){
            mindy_input_state.error=1;return -1;
        }
    }
    if(mindy_input_state.armed||INPUT_SLOT[1]!=0||
       *(const volatile uint8_t *)0x181db8e4u!=1){
        mindy_input_state.error=2;return -1;
    }
    if(!input_swap(STOCK_INPUT,(uintptr_t)mindy_input_callback)){
        mindy_input_state.error=3;return -1;
    }
    mindy_input_state.ever_installed=1;mindy_input_state.armed=1;
    mindy_input_state.acquired++;return 0;
}
/* Keep tap suppression during a bounded release/quiet interval. No waiting,
 * allocation or I/O ever occurs inside the callback itself. */
__attribute__((noinline)) int mindy_input_drain(int buttons,unsigned supported) {
    unsigned quiet=0,previous=mindy_input_state.suppressed;
    for(unsigned i=0;i<75;i++){
        uint32_t raw=0;
        if(!input_ours()){mindy_input_state.error=4;return -1;}
        if(os_sleep(20000)<0){mindy_input_state.error=5;return -1;}
        int n=os_read(buttons,&raw,4);
        unsigned count=mindy_input_state.suppressed;
        if(n==4&&!(raw&supported)&&count==previous)quiet++;else quiet=0;
        previous=count;
        mindy_input_state.quiet_ms=quiet*20;
        if(quiet>=15)return 0;
    }
    mindy_input_state.drain_timeout=1;mindy_input_state.error=6;return -1;
}
__attribute__((noinline)) int mindy_input_release(void) {
    if(!mindy_input_state.armed)return 0;
    if(INPUT_SLOT[1]!=0||!input_swap((uintptr_t)mindy_input_callback,STOCK_INPUT)){
        /* Someone else owns the slot now. Never overwrite their handler. */
        mindy_input_state.error=7;return -1;
    }
    mindy_input_state.armed=0;mindy_input_state.restored=1;return 0;
}

void mindy_diag(void) {
    struct video v;struct plane p;int fd=os_open("/dev/fb0",O_RW);
    if(fd<0){mindy_state.error=100;return;}int r=query(fd,&v,&p);os_close(fd);
    int buttons=os_open("/dev/buttons",O_RD|O_NONBLOCK);uint32_t supported=0,current=0;
    if(buttons>=0){os_ioctl(buttons,BUTTONS_SUPPORTED,&supported);os_read(buttons,&current,4);os_close(buttons);}
    mindy_state.supported=supported;mindy_state.last_buttons=current;mindy_state.error=r<0?(unsigned)(200-r):0;
    int power=-1;fd=os_open("/dev/fb0",O_RW);if(fd>=0){os_ioctl(fd,GET_POWER,&power);os_close(fd);}mindy_state.power=(unsigned)power;
    mindy_state.phase=2;os_printf("MINDYLAB diag result=%d buttons=%d supported=%08x state=%08x\n",r,buttons,supported,current);
}

void mindy_run(void) {
    if(mindy_state.phase==3||mindy_state.phase==4)return;
    int fd=-1,buttons=-1,locked=0,changed=0,input=0;uint8_t *backup=0;struct video v;struct plane p,original;
    char pid[16];unsigned pidlen=decimal(pid,(unsigned)os_pid());pid[pidlen++]='\n';
    mindy_state.phase=3;mindy_state.error=0;mindy_state.inputs=0;mindy_state.restored=0;mindy_state.lock_removed=0;
    mindy_state.pan_error=0;mindy_state.pan_errno=0;mindy_state.pan_clears=0;mindy_state.buffer_restored=0;
    mindy_state.gestures=0;mindy_state.raw_changes=0;
    fd=os_open("/dev/fb0",O_RW);if(fd<0){mindy_state.error=301;goto cleanup;}
    if(query(fd,&v,&p)<0){mindy_state.error=302;goto cleanup;}
    memcpy(&original,&p,sizeof(p));width=v.width;height=v.height;stride=p.stride;bpp=p.bpp;
    pixels=os_mmap(0,p.length,3,9,fd,0);if(pixels==(void *)-1||!pixels){pixels=0;mindy_state.error=303;goto cleanup;}
    backup=os_malloc(p.length);if(!backup){mindy_state.error=304;goto cleanup;}
    buttons=os_open("/dev/buttons",O_RD|O_NONBLOCK);if(buttons<0){mindy_state.error=305;goto cleanup;}
    uint32_t supported=0,last=0;os_ioctl(buttons,BUTTONS_SUPPORTED,&supported);os_read(buttons,&last,4);
    mindy_state.supported=supported;mindy_state.last_buttons=last;
    int lockfd=os_open(lock_path,O_WR|O_CREAT|O_EXCL,0600);
    if(lockfd<0){mindy_state.error=306;goto cleanup;}
    locked=1;int written=write_all(lockfd,pid,pidlen);os_close(lockfd);
    if(written<0){mindy_state.error=307;goto cleanup;}
    if(mindy_input_acquire()<0){mindy_state.error=312;goto cleanup;}
    input=1;
    os_sleep(150000);
    if(!own_lock(pid,pidlen)){mindy_state.error=308;goto cleanup;}
    memcpy(backup,pixels,p.length);changed=1;p.xoffset=0;p.yoffset=0;
    draw(0,120);if(present(fd,&p)<0){mindy_state.error=309;goto cleanup;}

    mindy_state.phase=4;os_printf("MINDYLAB READY: tap a physical button briefly. Auto exit 120 seconds.\n");
    for(unsigned tick=0;tick<(nav_mode?720000u:6000u);tick++){
        uint32_t now=last;int got=os_read(buttons,&now,4);unsigned redraw=0;
        if(got==4){
            /* G2 latches the gesture byte across the next press. Observed live:
             * 0x01080002 (pressed), 0x01080000 (released). Count completion only. */
            unsigned event=(now>>16)&255;
            unsigned gesture=now!=last&&!(now&supported)&&(now>>24)<2&&(event==8||event==9);
            if(now!=last)mindy_state.raw_changes++;
            if(gesture)mindy_state.gestures++;
            if(gesture){mindy_state.inputs++;redraw=1;os_printf("MINDYLAB INPUT count=%u raw=%08x\n",mindy_state.inputs,now);}
            last=now;mindy_state.last_buttons=now;}
        if(tick%50==0){if(tick)redraw=1;if(mobile_tick())break;
            if(!own_lock(pid,pidlen)){mindy_state.error=310;break;}}
        if(redraw){draw(mindy_state.inputs,nav_mode?0:120-tick/50);if(present(fd,&p)<0){mindy_state.error=311;break;}}
        if(!input_ours()){mindy_state.error=313;break;}
        mindy_state.ticks=tick;os_sleep(20000);
    }

cleanup:
    if(input&&mindy_input_drain(buttons,mindy_state.supported)<0&&!mindy_state.error)mindy_state.error=314;
    if(changed){memcpy(pixels,backup,p.length);mindy_state.buffer_restored=1;
        if(own_lock(pid,pidlen)&&present(fd,&original)>=0)mindy_state.restored=1;}
    if(locked&&own_lock(pid,pidlen)){if(os_unlink(lock_path)==0)mindy_state.lock_removed=1;}
    if(input&&mindy_input_release()<0&&!mindy_state.error)mindy_state.error=315;
    if(buttons>=0)os_close(buttons);if(backup)os_free(backup);
    if(pixels){os_munmap(pixels,p.length);pixels=0;}if(fd>=0)os_close(fd);
    mindy_state.phase=5;os_printf("MINDYLAB DONE error=%u inputs=%u restored=%u lock_removed=%u\n",
        mindy_state.error,mindy_state.inputs,mindy_state.restored,mindy_state.lock_removed);
}

static void mindy_init(void);
__attribute__((section(".init_array"),used)) static void (*const init_entry)(void)=mindy_init;


/* Persistent native MINDYLAB launcher for the known 1.0.1 AP code.
 * Constructor only creates one worker; all I/O is deferred to that worker.
 * Fixed authenticated-FTP RAM mailbox, no arbitrary commands or flash writer.
 */
typedef uint32_t u32;
#define os_create FN(int (*)(const char*,int,int,int (*)(int,char**),char *const*),0x001158a4)
static const char command_path[]="/tmp/mindy-mobile.cmd";
static const char status_path[]="/tmp/mindy-mobile.status";
static unsigned char mobile_nonce[16] __attribute__((section(".data")))={0};
static const unsigned char *session_nonce(void){return mobile_nonce;}
struct mobile_state {u32 magic,phase,pid,sequence,result,age,started,stop,returning,invalid;};
volatile struct mobile_state mobile_state={.magic=0x4d4f4249};
static volatile u32 shutdown_requested __attribute__((section(".data")))=0;
static int mobile_pid(void){return (int)mobile_state.pid;}
static u32 get32(const unsigned char *p){return p[0]|((u32)p[1]<<8)|((u32)p[2]<<16)|((u32)p[3]<<24);}
static void put32(unsigned char *p,u32 n){for(unsigned i=0;i<4;i++)p[i]=(unsigned char)(n>>(8*i));}
static u32 checkbytes(const unsigned char *p,unsigned n){u32 v=2166136261u;for(unsigned i=0;i<n;i++)v=(v^p[i])*16777619u;return v;}
static void age_tick(void){if(mobile_state.age<0x7fffffffu)mobile_state.age++;}
unsigned mobile_accept(const unsigned char *p,unsigned n){
    if(n!=64||get32(p)!=0x31434c4d||get32(p+4)!=4||get32(p+60)!=checkbytes(p,60))return 0;
    if(!same((const char*)p+16,(const char*)mobile_nonce,16))return 0;
    u32 op=get32(p+8),seq=get32(p+12);
    if((op!=1&&op!=2&&op!=3)||!seq||seq<=mobile_state.sequence||seq>10000)return 0;
    for(unsigned i=32;i<60;i++)if(p[i])return 0;
    mobile_state.sequence=seq;mobile_state.result=0;
    if((op==1||op==3)&&mobile_state.phase==2){mobile_state.result=2;return 0;}
    if(op==3&&(!nav_state.sequence||nav_state.link_age>=10)){mobile_state.result=8;return 0;}
    if(op==1||op==3)nav_mode=op==3;
    if(op==2)mobile_state.stop=1;
    return op;
}
static unsigned read_command(void){
    unsigned char data[65];int fd=os_open(command_path,O_RD);if(fd<0)return 0;
    unsigned n=0;while(n<65){int got=os_read(fd,data+n,65-n);if(got<=0)break;if((unsigned)got>65-n){n=0;break;}n+=(unsigned)got;}
    int closed=os_close(fd);if(closed<0)return 0;
    return mobile_accept(data,n);
}
static int mobile_publish(void){
    unsigned char record[128];memset(record,0,sizeof(record));
    const u32 fields[]={0x31534c4d,4,mobile_state.phase,mobile_state.sequence,mobile_state.result,
        mindy_state.phase,mindy_state.error,mindy_state.inputs,mindy_state.restored,
        mindy_state.lock_removed,mindy_state.buffer_restored,mobile_state.age,mobile_state.pid,
        mobile_state.started,mindy_state.pan_error,mindy_state.gestures};
    for(unsigned i=0;i<16;i++)put32(record+4*i,fields[i]);
    memcpy(record+64,mobile_nonce,16);put32(record+80,7);
    put32(record+84,nav_state.sequence);put32(record+88,nav_mode);
    put32(record+92,nav_state.link_age);put32(record+96,nav_state.state);
    put32(record+100,nav_state.layout);put32(record+104,nav_state.awake_requests);
    put32(record+124,checkbytes(record,124));
    int fd=os_open(status_path,O_WR|O_CREAT|O_TRUNC,0600);if(fd<0)return -1;
    int r=write_all(fd,record,sizeof(record));int c=os_close(fd);return r<0||c<0?-1:0;
}
static int mobile_tick(void){
    age_tick();nav_tick();read_command();
    if(!mobile_state.stop&&!shutdown_requested)nav_awake_tick();
    if(nav_mode&&nav_state.link_age>=30)mobile_state.stop=1;
    if(mobile_publish()<0)mobile_state.stop=1;
    return mobile_state.stop||shutdown_requested;
}
static int mobile_worker(int argc,char **argv){
    (void)argc;(void)argv;
    for(unsigned i=0;!mobile_state.pid&&i<1000;i++)os_sleep(1000);
    if(!mobile_state.pid){mobile_state.result=5;goto done;}
    /* NSH continues immediately; no framebuffer, Bluetooth or network start. */
    for(unsigned i=0;i<15;i++){if(shutdown_requested)goto done;if(os_sleep(1000000)<0)goto done;}
    int disabled=os_open("/data/ml.off",O_RD);
    if(disabled>=0){os_close(disabled);mobile_state.result=6;goto done;}
    int rng=os_open("/dev/random",O_RD|O_NONBLOCK);
    if(rng<0){mobile_state.result=7;goto done;}
    int n=os_read(rng,mobile_nonce,16);int closed=os_close(rng);
    unsigned nonzero=0;for(unsigned i=0;i<16;i++)nonzero|=mobile_nonce[i];
    if(n!=16||closed<0||!nonzero){mobile_state.result=7;goto done;}
    mobile_state.phase=1;
    if(mobile_publish()<0)goto done;
    while(!shutdown_requested){
        nav_tick();
        unsigned op=read_command();
        if(op==1||op==3){
            mobile_state.stop=0;mobile_state.phase=2;mobile_state.started++;
            if(mobile_publish()<0)break;
            mindy_run(); /* existing two-minute display and input routine */
            mobile_state.phase=1;mobile_state.stop=0;
            if(mindy_state.error){
                mobile_state.result=3;
                /* A pre-existing viewer lock means our screen was untouched. */
                if(mindy_state.error!=306){mobile_publish();break;}
            }else if(!mindy_state.restored||!mindy_state.lock_removed){mobile_state.result=3;mobile_publish();break;}
        }
        age_tick();if(mobile_publish()<0)break;
        if(os_sleep(1000000)<0)break;
    }
done:
    mobile_state.phase=3;mobile_state.returning=1;mobile_publish();return 0;
}
int mobile_start(void){
    if(mobile_state.phase||mobile_state.pid)return -1;
    mobile_state.phase=4;
    int pid=os_create("mindy_mobile",100,12288,mobile_worker,(char *const*)0);
    if(pid<=0){mobile_state.phase=3;mobile_state.result=4;mobile_state.returning=1;}
    else mobile_state.pid=(u32)pid;
    return pid;
}
int mobile_shutdown(void){shutdown_requested=1;mobile_state.stop=1;return 0;}
static void mindy_init(void){mindy_state.phase=1;mobile_start();}
