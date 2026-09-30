package dev.anchormc.core;

import java.util.UUID;

/** 클라이언트로 나가는 유일한 창구. 서버 월드는 건드리지 않는다. DecoyEngine 밖에서는 호출하지 않는다. */
public interface Display {
    void show(UUID player, Pos pos, Host host);

    /** 진짜 블록을 다시 보낸다. */
    void hide(UUID player, Pos pos);
}
