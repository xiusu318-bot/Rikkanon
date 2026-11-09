#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#define CONF_PATH "/run/rikkahub-net.conf"
#define MAX_HOSTS 512
#define MAX_HOST_LEN 255

typedef struct {
    uint32_t fake_ip_host_order;      /* Fallback 198.18.x.y */
    uint32_t resolved_ip_net_order;   /* Real public IPv4 resolved via remote proxy */
    char domain[MAX_HOST_LEN + 1];
} host_entry_t;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static host_entry_t g_hosts[MAX_HOSTS];
static uint32_t g_next_idx = 1;

static int (*real_connect)(int, const struct sockaddr *, socklen_t) = NULL;
static int (*real_getaddrinfo)(const char *, const char *, const struct addrinfo *, struct addrinfo **) = NULL;
static struct hostent *(*real_gethostbyname)(const char *) = NULL;
static int (*real_gethostbyname_r)(const char *, struct hostent *, char *, size_t, struct hostent **, int *) = NULL;

static void init_real_funcs(void) {
    if (!real_connect) {
        real_connect = (int (*)(int, const struct sockaddr *, socklen_t))dlsym(RTLD_NEXT, "connect");
    }
    if (!real_getaddrinfo) {
        real_getaddrinfo = (int (*)(const char *, const char *, const struct addrinfo *, struct addrinfo **))dlsym(RTLD_NEXT, "getaddrinfo");
    }
    if (!real_gethostbyname) {
        real_gethostbyname = (struct hostent *(*)(const char *))dlsym(RTLD_NEXT, "gethostbyname");
    }
    if (!real_gethostbyname_r) {
        real_gethostbyname_r = (int (*)(const char *, struct hostent *, char *, size_t, struct hostent **, int *))dlsym(RTLD_NEXT, "gethostbyname_r");
    }
}

/* Read active bridge port from /run/rikkahub-net.conf; returns 0 when direct/no-proxy */
static int read_bridge_port(void) {
    int fd = open(CONF_PATH, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    char buf[64];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 0;
    buf[n] = '\0';
    int port = atoi(buf);
    return (port > 0 && port <= 65535) ? port : 0;
}

static int is_local_or_numeric_host(const char *node) {
    if (!node || !node[0]) return 1;
    if (strcasecmp(node, "localhost") == 0 ||
        strcasecmp(node, "ip6-localhost") == 0 ||
        strcasecmp(node, "ip6-loopback") == 0 ||
        strcmp(node, "::1") == 0) {
        return 1;
    }
    struct in_addr addr4;
    if (inet_pton(AF_INET, node, &addr4) == 1) {
        return 1;
    }
    struct in6_addr addr6;
    if (inet_pton(AF_INET6, node, &addr6) == 1) {
        return 1;
    }
    return 0;
}

static int write_all(int fd, const void *buf, size_t len, int timeout_ms) {
    const uint8_t *p = (const uint8_t *)buf;
    size_t sent = 0;
    while (sent < len) {
        struct pollfd pfd;
        pfd.fd = fd;
        pfd.events = POLLOUT;
        int pr = poll(&pfd, 1, timeout_ms);
        if (pr <= 0) return -1;
        ssize_t n = send(fd, p + sent, len - sent, MSG_NOSIGNAL);
        if (n <= 0) {
            if (n < 0 && errno == EINTR) continue;
            return -1;
        }
        sent += (size_t)n;
    }
    return 0;
}

static int read_exact(int fd, void *buf, size_t len, int timeout_ms) {
    uint8_t *p = (uint8_t *)buf;
    size_t got = 0;
    while (got < len) {
        struct pollfd pfd;
        pfd.fd = fd;
        pfd.events = POLLIN;
        int pr = poll(&pfd, 1, timeout_ms);
        if (pr <= 0) return -1;
        ssize_t n = recv(fd, p + got, len - got, 0);
        if (n <= 0) {
            if (n < 0 && errno == EINTR) continue;
            return -1;
        }
        got += (size_t)n;
    }
    return 0;
}

/* Ask host loopback bridge to resolve domain remotely via proxy (SOCKS5 CMD=0xF0) */
static uint32_t resolve_remote_ipv4_via_bridge(int bridge_port, const char *domain) {
    init_real_funcs();
    if (!real_connect || !domain) return 0;
    size_t dlen = strlen(domain);
    if (dlen == 0 || dlen > 255) return 0;

    int s = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s < 0) return 0;

    struct sockaddr_in baddr;
    memset(&baddr, 0, sizeof(baddr));
    baddr.sin_family = AF_INET;
    baddr.sin_port = htons((uint16_t)bridge_port);
    baddr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    if (real_connect(s, (struct sockaddr *)&baddr, sizeof(baddr)) != 0) {
        close(s);
        return 0;
    }

    uint8_t req[3 + 4 + 256 + 2];
    req[0] = 0x05;
    req[1] = 0x01;
    req[2] = 0x00;
    if (write_all(s, req, 3, 3000) != 0) {
        close(s);
        return 0;
    }
    uint8_t greet_reply[2];
    if (read_exact(s, greet_reply, 2, 3000) != 0 || greet_reply[0] != 0x05 || greet_reply[1] != 0x00) {
        close(s);
        return 0;
    }

    /* Custom CMD 0xF0 = RESOLVE_DOMAIN */
    req[0] = 0x05;
    req[1] = 0xF0;
    req[2] = 0x00;
    req[3] = 0x03;
    req[4] = (uint8_t)dlen;
    memcpy(req + 5, domain, dlen);
    req[5 + dlen] = 0;
    req[6 + dlen] = 0;
    if (write_all(s, req, 7 + dlen, 4000) != 0) {
        close(s);
        return 0;
    }

    uint8_t rep[10];
    if (read_exact(s, rep, 10, 6000) != 0 || rep[0] != 0x05 || rep[1] != 0x00 || rep[3] != 0x01) {
        close(s);
        return 0;
    }
    close(s);

    uint32_t ip_net = 0;
    memcpy(&ip_net, rep + 4, 4);
    return ip_net;
}

static uint32_t register_or_lookup_domain(int bridge_port, const char *domain) {
    pthread_mutex_lock(&g_lock);
    for (uint32_t i = 0; i < MAX_HOSTS; i++) {
        if (g_hosts[i].domain[0] && strcasecmp(g_hosts[i].domain, domain) == 0) {
            uint32_t ret = g_hosts[i].resolved_ip_net_order
                ? g_hosts[i].resolved_ip_net_order
                : htonl(g_hosts[i].fake_ip_host_order);
            pthread_mutex_unlock(&g_lock);
            return ret;
        }
    }
    pthread_mutex_unlock(&g_lock);

    uint32_t real_ip_net = resolve_remote_ipv4_via_bridge(bridge_port, domain);

    pthread_mutex_lock(&g_lock);
    for (uint32_t i = 0; i < MAX_HOSTS; i++) {
        if (g_hosts[i].domain[0] && strcasecmp(g_hosts[i].domain, domain) == 0) {
            if (real_ip_net != 0) g_hosts[i].resolved_ip_net_order = real_ip_net;
            uint32_t ret = g_hosts[i].resolved_ip_net_order
                ? g_hosts[i].resolved_ip_net_order
                : htonl(g_hosts[i].fake_ip_host_order);
            pthread_mutex_unlock(&g_lock);
            return ret;
        }
    }

    uint32_t slot = g_next_idx % MAX_HOSTS;
    uint32_t fake_host = (198U << 24) | (18U << 16) | ((g_next_idx & 0x7FFFU) + 1U);
    g_next_idx++;

    strncpy(g_hosts[slot].domain, domain, MAX_HOST_LEN);
    g_hosts[slot].domain[MAX_HOST_LEN] = '\0';
    g_hosts[slot].fake_ip_host_order = fake_host;
    g_hosts[slot].resolved_ip_net_order = real_ip_net;

    uint32_t ret = real_ip_net ? real_ip_net : htonl(fake_host);
    pthread_mutex_unlock(&g_lock);
    return ret;
}

static int find_domain_by_ip(uint32_t ip_net_order, char *out_domain, size_t out_len) {
    uint32_t ip_host = ntohl(ip_net_order);
    pthread_mutex_lock(&g_lock);
    for (uint32_t i = 0; i < MAX_HOSTS; i++) {
        if (!g_hosts[i].domain[0]) continue;
        if (g_hosts[i].fake_ip_host_order == ip_host ||
            (g_hosts[i].resolved_ip_net_order != 0 && g_hosts[i].resolved_ip_net_order == ip_net_order)) {
            strncpy(out_domain, g_hosts[i].domain, out_len - 1);
            out_domain[out_len - 1] = '\0';
            pthread_mutex_unlock(&g_lock);
            return 1;
        }
    }
    pthread_mutex_unlock(&g_lock);
    return 0;
}

__attribute__((visibility("default")))
int getaddrinfo(const char *node, const char *service,
                const struct addrinfo *hints, struct addrinfo **res) {
    init_real_funcs();
    int bridge_port = read_bridge_port();
    if (bridge_port <= 0 || !node || is_local_or_numeric_host(node)) {
        return real_getaddrinfo(node, service, hints, res);
    }
    if (hints && (hints->ai_flags & AI_NUMERICHOST)) {
        return real_getaddrinfo(node, service, hints, res);
    }
    if (hints && hints->ai_family == AF_INET6) {
        return EAI_NONAME;
    }

    uint32_t ip_net = register_or_lookup_domain(bridge_port, node);
    struct in_addr in;
    in.s_addr = ip_net;
    char ip_str[INET_ADDRSTRLEN];
    if (!inet_ntop(AF_INET, &in, ip_str, sizeof(ip_str))) {
        return real_getaddrinfo(node, service, hints, res);
    }

    struct addrinfo num_hints;
    if (hints) {
        num_hints = *hints;
    } else {
        memset(&num_hints, 0, sizeof(num_hints));
    }
    num_hints.ai_family = AF_INET;
    num_hints.ai_flags = (num_hints.ai_flags | AI_NUMERICHOST) & ~AI_CANONNAME;

    int rc = real_getaddrinfo(ip_str, service, &num_hints, res);
    if (rc == 0 && res && *res && (!hints || (hints->ai_flags & AI_CANONNAME))) {
        if (!(*res)->ai_canonname) {
            (*res)->ai_canonname = strdup(node);
        }
    }
    return rc;
}

__attribute__((visibility("default")))
struct hostent *gethostbyname(const char *name) {
    init_real_funcs();
    int bridge_port = read_bridge_port();
    if (bridge_port <= 0 || !name || is_local_or_numeric_host(name)) {
        return real_gethostbyname(name);
    }

    static __thread struct hostent he;
    static __thread char cname[MAX_HOST_LEN + 1];
    static __thread struct in_addr addr;
    static __thread char *addr_list[2];
    static __thread char *aliases[1];

    uint32_t ip_net = register_or_lookup_domain(bridge_port, name);
    strncpy(cname, name, MAX_HOST_LEN);
    cname[MAX_HOST_LEN] = '\0';
    addr.s_addr = ip_net;
    addr_list[0] = (char *)&addr;
    addr_list[1] = NULL;
    aliases[0] = NULL;

    he.h_name = cname;
    he.h_aliases = aliases;
    he.h_addrtype = AF_INET;
    he.h_length = sizeof(struct in_addr);
    he.h_addr_list = addr_list;
    return &he;
}

__attribute__((visibility("default")))
int gethostbyname_r(const char *name, struct hostent *ret, char *buf, size_t buflen,
                    struct hostent **result, int *h_errnop) {
    init_real_funcs();
    int bridge_port = read_bridge_port();
    if (bridge_port <= 0 || !name || is_local_or_numeric_host(name)) {
        return real_gethostbyname_r(name, ret, buf, buflen, result, h_errnop);
    }

    size_t name_len = strlen(name) + 1;
    size_t needed = name_len + sizeof(struct in_addr) + 2 * sizeof(char *) + sizeof(char *) + 32;
    if (!ret || !buf || !result || buflen < needed) {
        if (result) *result = NULL;
        if (h_errnop) *h_errnop = NETDB_INTERNAL;
        return ERANGE;
    }

    uint32_t ip_net = register_or_lookup_domain(bridge_port, name);

    char *p = buf;
    memcpy(p, name, name_len);
    ret->h_name = p;
    p += name_len;

    p = (char *)(((uintptr_t)p + 7U) & ~7U);
    struct in_addr *addr_ptr = (struct in_addr *)p;
    addr_ptr->s_addr = ip_net;
    p += sizeof(struct in_addr);

    p = (char *)(((uintptr_t)p + 7U) & ~7U);
    char **addr_list = (char **)p;
    addr_list[0] = (char *)addr_ptr;
    addr_list[1] = NULL;
    p += 2 * sizeof(char *);

    char **aliases = (char **)p;
    aliases[0] = NULL;

    ret->h_aliases = aliases;
    ret->h_addrtype = AF_INET;
    ret->h_length = sizeof(struct in_addr);
    ret->h_addr_list = addr_list;
    *result = ret;
    if (h_errnop) *h_errnop = 0;
    return 0;
}

__attribute__((visibility("default")))
int connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    init_real_funcs();
    int bridge_port = read_bridge_port();
    if (bridge_port <= 0 || !addr) {
        return real_connect(sockfd, addr, addrlen);
    }

    int type = 0;
    socklen_t typelen = sizeof(type);
    if (getsockopt(sockfd, SOL_SOCKET, SO_TYPE, &type, &typelen) != 0 || type != SOCK_STREAM) {
        return real_connect(sockfd, addr, addrlen);
    }

    uint32_t dst_ip_net = 0;
    uint16_t dst_port_net = 0;

    if (addr->sa_family == AF_INET && addrlen >= (socklen_t)sizeof(struct sockaddr_in)) {
        const struct sockaddr_in *sin = (const struct sockaddr_in *)addr;
        dst_ip_net = sin->sin_addr.s_addr;
        dst_port_net = sin->sin_port;
    } else if (addr->sa_family == AF_INET6 && addrlen >= (socklen_t)sizeof(struct sockaddr_in6)) {
        const struct sockaddr_in6 *sin6 = (const struct sockaddr_in6 *)addr;
        if (IN6_IS_ADDR_V4MAPPED(&sin6->sin6_addr)) {
            memcpy(&dst_ip_net, &sin6->sin6_addr.s6_addr[12], 4);
            dst_port_net = sin6->sin6_port;
        } else {
            return real_connect(sockfd, addr, addrlen);
        }
    } else {
        return real_connect(sockfd, addr, addrlen);
    }

    uint32_t dst_ip_host = ntohl(dst_ip_net);
    uint16_t dst_port_host = ntohs(dst_port_net);

    /* Bypass loopback (127.0.0.0/8), 0.0.0.0, multicast/broadcast */
    if ((dst_ip_host >> 24) == 127 || dst_ip_host == 0 || (dst_ip_host >> 28) >= 14) {
        return real_connect(sockfd, addr, addrlen);
    }

    int orig_flags = fcntl(sockfd, F_GETFL, 0);
    int was_nonblock = (orig_flags >= 0) && (orig_flags & O_NONBLOCK);
    if (was_nonblock) {
        fcntl(sockfd, F_SETFL, orig_flags & ~O_NONBLOCK);
    }

    int rc = -1;
    if (addr->sa_family == AF_INET6) {
        struct sockaddr_in6 b6;
        memset(&b6, 0, sizeof(b6));
        b6.sin6_family = AF_INET6;
        b6.sin6_port = htons((uint16_t)bridge_port);
        b6.sin6_addr.s6_addr[10] = 0xFF;
        b6.sin6_addr.s6_addr[11] = 0xFF;
        b6.sin6_addr.s6_addr[12] = 127;
        b6.sin6_addr.s6_addr[15] = 1;
        rc = real_connect(sockfd, (struct sockaddr *)&b6, sizeof(b6));
    } else {
        struct sockaddr_in b4;
        memset(&b4, 0, sizeof(b4));
        b4.sin_family = AF_INET;
        b4.sin_port = htons((uint16_t)bridge_port);
        b4.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        rc = real_connect(sockfd, (struct sockaddr *)&b4, sizeof(b4));
    }

    if (rc != 0) {
        if (was_nonblock) fcntl(sockfd, F_SETFL, orig_flags);
        return rc;
    }

    uint8_t greet[3] = { 0x05, 0x01, 0x00 };
    if (write_all(sockfd, greet, 3, 15000) != 0) {
        if (was_nonblock) fcntl(sockfd, F_SETFL, orig_flags);
        errno = ECONNREFUSED;
        return -1;
    }
    uint8_t greet_reply[2];
    if (read_exact(sockfd, greet_reply, 2, 15000) != 0 || greet_reply[0] != 0x05 || greet_reply[1] != 0x00) {
        if (was_nonblock) fcntl(sockfd, F_SETFL, orig_flags);
        errno = ECONNREFUSED;
        return -1;
    }

    char domain[MAX_HOST_LEN + 1];
    uint8_t req[7 + MAX_HOST_LEN];
    size_t req_len = 0;

    if (find_domain_by_ip(dst_ip_net, domain, sizeof(domain))) {
        size_t dlen = strlen(domain);
        req[0] = 0x05;
        req[1] = 0x01; /* CONNECT */
        req[2] = 0x00;
        req[3] = 0x03; /* ATYP = DOMAINNAME */
        req[4] = (uint8_t)dlen;
        memcpy(req + 5, domain, dlen);
        req[5 + dlen] = (uint8_t)((dst_port_host >> 8) & 0xFF);
        req[6 + dlen] = (uint8_t)(dst_port_host & 0xFF);
        req_len = 7 + dlen;
    } else {
        req[0] = 0x05;
        req[1] = 0x01; /* CONNECT */
        req[2] = 0x00;
        req[3] = 0x01; /* ATYP = IPv4 */
        memcpy(req + 4, &dst_ip_net, 4);
        req[8] = (uint8_t)((dst_port_host >> 8) & 0xFF);
        req[9] = (uint8_t)(dst_port_host & 0xFF);
        req_len = 10;
    }

    if (write_all(sockfd, req, req_len, 20000) != 0) {
        if (was_nonblock) fcntl(sockfd, F_SETFL, orig_flags);
        errno = ETIMEDOUT;
        return -1;
    }

    uint8_t rep_hdr[4];
    if (read_exact(sockfd, rep_hdr, 4, 20000) != 0 || rep_hdr[0] != 0x05 || rep_hdr[1] != 0x00) {
        if (was_nonblock) fcntl(sockfd, F_SETFL, orig_flags);
        errno = ECONNREFUSED;
        return -1;
    }

    uint8_t discard[256];
    if (rep_hdr[3] == 0x01) {
        if (read_exact(sockfd, discard, 6, 5000) != 0) {
            if (was_nonblock) fcntl(sockfd, F_SETFL, orig_flags);
            errno = ECONNREFUSED;
            return -1;
        }
    } else if (rep_hdr[3] == 0x03) {
        uint8_t alen = 0;
        if (read_exact(sockfd, &alen, 1, 5000) != 0 ||
            read_exact(sockfd, discard, (size_t)alen + 2, 5000) != 0) {
            if (was_nonblock) fcntl(sockfd, F_SETFL, orig_flags);
            errno = ECONNREFUSED;
            return -1;
        }
    } else if (rep_hdr[3] == 0x04) {
        if (read_exact(sockfd, discard, 18, 5000) != 0) {
            if (was_nonblock) fcntl(sockfd, F_SETFL, orig_flags);
            errno = ECONNREFUSED;
            return -1;
        }
    }

    if (was_nonblock) {
        fcntl(sockfd, F_SETFL, orig_flags);
    }
    return 0;
}
