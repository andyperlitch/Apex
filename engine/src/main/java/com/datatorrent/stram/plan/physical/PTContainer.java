/**
 * Copyright (C) 2015 DataTorrent, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datatorrent.stram.plan.physical;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;

import com.datatorrent.stram.Journal.Recoverable;

/**
 *
 * Representation of a container for physical objects of DAG to be placed in
 * <p>
 * <br>
 * References the actual container assigned by the resource manager which
 * hosts the streaming operators in the execution layer.<br>
 * The container reference may change throughout the lifecycle of the
 * application due to failure/recovery or scheduler decisions in general. <br>
 *
 * @since 0.3.5
 */
public class PTContainer implements java.io.Serializable
{
  private static final long serialVersionUID = 201312112033L;

  public final static Recoverable SET_CONTAINER_STATE = new SetContainerState();

  public enum State {
    NEW,
    ALLOCATED,
    ACTIVE,
    KILLED
  }

  /**
   * Resource priority is logged so that on restore, pending resource requests can be matched to the containers.
   */
  private static class SetContainerState implements Recoverable
  {
    private final PTContainer container;

    private SetContainerState()
    {
      container = null;
    }

    private SetContainerState(PTContainer container)
    {
      this.container = container;
    }

    @Override
    public void read(final Object object, final Input in) throws KryoException
    {
      PhysicalPlan plan = (PhysicalPlan)object;

      int containerId = in.readInt();

      for (PTContainer c : plan.getContainers())
      {
        if (c.getId() == containerId) {
          int stateOrd = in.readInt();
          c.state = PTContainer.State.values()[stateOrd];
          c.containerId = in.readString();
          c.resourceRequestPriority = in.readInt();
          c.requiredMemoryMB = in.readInt();
          c.allocatedMemoryMB = in.readInt();
          c.requiredVCores = in.readInt();
          c.allocatedVCores = in.readInt();
          String bufferServerHost = in.readString();
          if (bufferServerHost != null) {
            c.bufferServerAddress = InetSocketAddress.createUnresolved(bufferServerHost, in.readInt());
          }
          c.host = in.readString();
          c.nodeHttpAddress = in.readString();
          break;
        }
      }
    }

    @Override
    public void write(final Output out) throws KryoException
    {
      out.writeInt(container.getId());
      // state
      out.writeInt(container.getState().ordinal());
      // external id
      out.writeString(container.getExternalId());
      // resource priority
      out.writeInt(container.getResourceRequestPriority());
      // memory required
      out.writeInt(container.getRequiredMemoryMB());
      // memory allocated
      out.writeInt(container.getAllocatedMemoryMB());
      // vcores required
      out.writeInt(container.getRequiredVCores());
      // vcores allocated
      out.writeInt(container.getAllocatedVCores());
      // buffer server address
      InetSocketAddress addr = container.bufferServerAddress;
      if (addr != null) {
        out.writeString(addr.getHostName());
        out.writeInt(addr.getPort());
      } else {
        out.writeString(null);
      }
      // host
      out.writeString(container.host);
      out.writeString(container.nodeHttpAddress);
    }
  }

  private volatile PTContainer.State state = State.NEW;
  private int requiredMemoryMB;
  private int allocatedMemoryMB;
  private int resourceRequestPriority;
  private int requiredVCores;
  private int allocatedVCores;

  private final PhysicalPlan plan;
  private final int seq;
  List<PTOperator> operators = new ArrayList<PTOperator>();

  // execution layer properties
  private String containerId; // assigned yarn container id
  public String host;
  public InetSocketAddress bufferServerAddress;
  public String nodeHttpAddress;
  int restartAttempts;
  private long startedTime = -1;
  private long finishedTime = -1;

  PTContainer(PhysicalPlan plan) {
    this.plan = plan;
    this.seq = plan.containerSeq.incrementAndGet();
  }

  public Recoverable getSetContainerState() {
    return new SetContainerState(this);
  }

  public PhysicalPlan getPlan() {
    return plan;
  }

  public PTContainer.State getState() {
    return this.state;
  }

  public void setState(PTContainer.State state) {
    this.state = state;
  }

  public int getRequiredMemoryMB() {
    return requiredMemoryMB;
  }

  public void setRequiredMemoryMB(int requiredMemoryMB) {
    this.requiredMemoryMB = requiredMemoryMB;
  }

  public int getAllocatedMemoryMB() {
    return allocatedMemoryMB;
  }

  public int getRequiredVCores()
  {
    return requiredVCores;
  }

  public void setRequiredVCores(int requiredVCores)
  {
    this.requiredVCores = requiredVCores;
  }

  public int getAllocatedVCores()
  {
    return allocatedVCores;
  }

  public void setAllocatedVCores(int allocatedVCores)
  {
    this.allocatedVCores = allocatedVCores;
  }

  public void setAllocatedMemoryMB(int allocatedMemoryMB) {
    this.allocatedMemoryMB = allocatedMemoryMB;
  }

  public int getResourceRequestPriority() {
    return resourceRequestPriority;
  }

  public void setResourceRequestPriority(int resourceRequestPriority) {
    this.resourceRequestPriority = resourceRequestPriority;
  }

  public List<PTOperator> getOperators() {
    return operators;
  }

  public int getId() {
    return this.seq;
  }

  public String getExternalId() {
    return this.containerId;
  }

  public void setExternalId(String id) {
    this.containerId = id;
  }

  public long getStartedTime()
  {
    return startedTime;
  }

  public void setStartedTime(long startedTime)
  {
    this.startedTime = startedTime;
  }

  public long getFinishedTime()
  {
    return finishedTime;
  }

  public void setFinishedTime(long finishedTime)
  {
    this.finishedTime = finishedTime;
  }

  public String toIdStateString() {
    return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE).
        append("id", ""+seq + "(" + this.containerId + ")").
        append("state", this.getState()).
        toString();
  }

  /**
   *
   * @return String
   */
  @Override
  public String toString() {
    return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE).
        append("id", ""+seq + "(" + this.containerId + ")").
        append("state", this.getState()).
        append("operators", this.operators).
        toString();
  }
}
