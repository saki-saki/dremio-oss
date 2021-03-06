/*
 * Copyright (C) 2017-2018 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.planner.logical;

import java.util.List;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.InvalidRelException;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.util.trace.CalciteTrace;
import org.slf4j.Logger;

import com.google.common.collect.Lists;

/**
 * Rule that converts a {@link org.apache.calcite.rel.logical.LogicalJoin} to a {@link JoinRel}, which is implemented by Dremio "join" operation.
 */
public class JoinRule extends RelOptRule {
  public static final RelOptRule INSTANCE = new JoinRule();
  protected static final Logger tracer = CalciteTrace.getPlannerTracer();

  private JoinRule() {
    super(
        RelOptHelper.any(LogicalJoin.class, Convention.NONE),
        "JoinRule");
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    final LogicalJoin join = (LogicalJoin) call.rel(0);
    final RelNode left = join.getLeft();
    final RelNode right = join.getRight();
    final RelTraitSet traits = join.getTraitSet().plus(Rel.LOGICAL);

    final RelNode convertedLeft = convert(left, left.getTraitSet().plus(Rel.LOGICAL).simplify());
    final RelNode convertedRight = convert(right, right.getTraitSet().plus(Rel.LOGICAL).simplify());

    List<Integer> leftKeys = Lists.newArrayList();
    List<Integer> rightKeys = Lists.newArrayList();
    List<Boolean> filterNulls = Lists.newArrayList();

    boolean addFilter = false;
    RexNode origJoinCondition = join.getCondition();
    RexNode newJoinCondition = origJoinCondition;

    RexNode remaining = RelOptUtil.splitJoinCondition(convertedLeft, convertedRight, origJoinCondition, leftKeys, rightKeys, filterNulls);
    boolean hasEquijoins = leftKeys.size() == rightKeys.size() && leftKeys.size() > 0 ;

    // If the join involves equijoins and non-equijoins, then we can process the non-equijoins through
    // a filter right after the join
    // DRILL-1337: We can only pull up a non-equivjoin filter for INNER join.
    // For OUTER join, pulling up a non-eqivjoin filter will lead to incorrectly discarding qualified rows.
    if (! remaining.isAlwaysTrue()) {
      if (hasEquijoins && join.getJoinType()== JoinRelType.INNER) {
        addFilter = true;
        newJoinCondition = buildJoinCondition(convertedLeft, convertedRight, leftKeys, rightKeys, filterNulls, join.getCluster().getRexBuilder());
      }
    } else {
      newJoinCondition = buildJoinCondition(convertedLeft, convertedRight, leftKeys, rightKeys, filterNulls, join.getCluster().getRexBuilder());
    }

    try {
      if (!addFilter) {
       RelNode joinRel = new JoinRel(join.getCluster(), traits, convertedLeft, convertedRight, newJoinCondition,
                                         join.getJoinType(), leftKeys, rightKeys);
       call.transformTo(joinRel);
      } else {
        RelNode joinRel = new JoinRel(join.getCluster(), traits, convertedLeft, convertedRight, newJoinCondition,
                                           join.getJoinType(), leftKeys, rightKeys);
        call.transformTo(new FilterRel(join.getCluster(), traits, joinRel, remaining));
      }
    } catch (InvalidRelException e) {
      tracer.warn(e.toString());
    }
  }

  private RexNode buildJoinCondition(RelNode convertedLeft, RelNode convertedRight, List<Integer> leftKeys,
      List<Integer> rightKeys, List<Boolean> filterNulls, RexBuilder builder) {
    List<RexNode> equijoinList = Lists.newArrayList();
    final int numLeftFields = convertedLeft.getRowType().getFieldCount();
    List<RelDataTypeField> leftTypes = convertedLeft.getRowType().getFieldList();
    List<RelDataTypeField> rightTypes = convertedRight.getRowType().getFieldList();

    for (int i=0; i < leftKeys.size(); i++) {
      int leftKeyOrdinal = leftKeys.get(i).intValue();
      int rightKeyOrdinal = rightKeys.get(i).intValue();

      equijoinList.add(builder.makeCall(
          filterNulls.get(i) ? SqlStdOperatorTable.EQUALS : SqlStdOperatorTable.IS_NOT_DISTINCT_FROM,
          builder.makeInputRef(leftTypes.get(leftKeyOrdinal).getType(), leftKeyOrdinal),
          builder.makeInputRef(rightTypes.get(rightKeyOrdinal).getType(), rightKeyOrdinal + numLeftFields)
      ));
    }
    return RexUtil.composeConjunction(builder, equijoinList, false);
  }
}
