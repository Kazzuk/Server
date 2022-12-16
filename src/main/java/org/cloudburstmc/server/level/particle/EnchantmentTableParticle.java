package org.cloudburstmc.server.level.particle;

import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.ParticleType;

/**
 * Created on 2015/11/21 by xtypr.
 * Package cn.nukkit.level.particle in project Nukkit .
 */
public class EnchantmentTableParticle extends GenericParticle {
    public EnchantmentTableParticle(Vector3f pos) {
        super(pos, ParticleType.ENCHANTING_TABLE);
    }
}
