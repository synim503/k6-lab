# Образ «виртуального генератора»: Debian 12 + systemd + sshd. Только для разработки и тестов SSH-деплоя.
# Пользователи: deploy/deploy (sudo с паролем), k6user/k6user (без sudo, для пользовательского режима).
FROM jrei/systemd-debian:12

COPY apt-retry.conf /etc/apt/apt.conf.d/99retry

# Зеркало Debian можно сменить: docker build --build-arg DEBIAN_MIRROR=ftp.de.debian.org ...
ARG DEBIAN_MIRROR=deb.debian.org
RUN sed -i "s|deb.debian.org|${DEBIAN_MIRROR}|g" /etc/apt/sources.list.d/debian.sources 2>/dev/null || true

# Прокси/CDN иногда отвечают 502 на отдельные пакеты: повторяем установку, скачанные .deb остаются в кеше apt
RUN apt-get update \
 && for i in 1 2 3 4 5 6 7 8; do \
      DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        openssh-server sudo curl netcat-openbsd procps iproute2 ca-certificates tar gzip cron util-linux coreutils \
      && break || { echo "apt retry $i"; sleep 3; }; \
    done \
 && command -v sshd >/dev/null || test -x /usr/sbin/sshd \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir -p /run/sshd \
 && useradd -m -s /bin/bash deploy && echo 'deploy:deploy' | chpasswd \
 && echo 'deploy ALL=(ALL) ALL' > /etc/sudoers.d/deploy && chmod 440 /etc/sudoers.d/deploy \
 && useradd -m -s /bin/bash k6user && echo 'k6user:k6user' | chpasswd \
 && sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication yes/' /etc/ssh/sshd_config \
 && systemctl enable ssh cron

# В контейнере systemd-user-sessions не запускается, и /run/nologin (pam_nologin) блокирует вход обычных пользователей
COPY ssh-nologin.conf /etc/systemd/system/ssh.service.d/nologin.conf

EXPOSE 22
