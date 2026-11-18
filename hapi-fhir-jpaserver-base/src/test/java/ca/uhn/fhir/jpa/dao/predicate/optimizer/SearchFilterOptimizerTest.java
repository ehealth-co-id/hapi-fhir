package ca.uhn.fhir.jpa.dao.predicate.optimizer;

import ca.uhn.fhir.jpa.dao.predicate.SearchFilterParser;
import ca.uhn.fhir.rest.api.RestSearchParameterTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SearchFilterOptimizerTest {

	private static final Map<String, RestSearchParameterTypeEnum> TYPES = Map.of(
			"location", RestSearchParameterTypeEnum.REFERENCE,
			"date", RestSearchParameterTypeEnum.DATE,
			"appointment-type", RestSearchParameterTypeEnum.TOKEN,
			"status", RestSearchParameterTypeEnum.TOKEN,
			"name", RestSearchParameterTypeEnum.STRING);

	private final FilterOptimizerContext myContext = name -> Optional.ofNullable(TYPES.get(name));
	private final SearchFilterOptimizer myOptimizer = SearchFilterOptimizer.createDefault();

	private SearchFilterParser.BaseFilter parse(String theFilter) throws Exception {
		return SearchFilterParser.parse(theFilter);
	}

	private SearchFilterParser.BaseFilter optimize(String theFilter) throws Exception {
		return myOptimizer.optimize(parse(theFilter), myContext);
	}

	@Test
	public void testProductionFilter_MergesTokenOrIntoOneList() throws Exception {
		String filter = "(location eq Location/1) and (date eq ge2024-01-01) and (date eq le2024-01-31) and "
				+ "((appointment-type eq WALKIN) or (appointment-type eq EMERGENCY))";
		SearchFilterParser.BaseFilter before = parse(filter);
		String beforeText = before.toString();

		SearchFilterParser.BaseFilter result = myOptimizer.optimize(before, myContext);

		FilterNaryLogical and = assertInstanceOf(FilterNaryLogical.class, result);
		assertEquals(SearchFilterParser.FilterLogicalOperation.and, and.getOperation());
		assertEquals(4, and.getOperands().size());
		assertInstanceOf(SearchFilterParser.FilterParameter.class, and.getOperands().get(0));
		assertInstanceOf(SearchFilterParser.FilterParameter.class, and.getOperands().get(1));
		assertInstanceOf(SearchFilterParser.FilterParameter.class, and.getOperands().get(2));
		FilterParameterOrList orList = assertInstanceOf(FilterParameterOrList.class, and.getOperands().get(3));
		assertEquals("appointment-type", orList.getParamName());
		assertEquals(SearchFilterParser.CompareOperation.eq, orList.getOperation());
		assertEquals(List.of("WALKIN", "EMERGENCY"), orList.getValues());

		// The parsed tree is not mutated
		assertEquals(beforeText, before.toString());
	}

	@Test
	public void testThreeWayOr_MergesIntoSingleList() throws Exception {
		SearchFilterParser.BaseFilter result =
				optimize("appointment-type eq A or appointment-type eq B or appointment-type eq C");
		FilterParameterOrList orList = assertInstanceOf(FilterParameterOrList.class, result);
		assertEquals(List.of("A", "B", "C"), orList.getValues());
	}

	@Test
	public void testNestedGroups_AreStrippedAndMerged() throws Exception {
		SearchFilterParser.BaseFilter result =
				optimize("((appointment-type eq A) or ((appointment-type eq B) or (appointment-type eq C)))");
		FilterParameterOrList orList = assertInstanceOf(FilterParameterOrList.class, result);
		assertEquals(List.of("A", "B", "C"), orList.getValues());
	}

	@Test
	public void testMixedOr_MergesOnlySameParameter_KeepsOthersInPlace() throws Exception {
		FilterNaryLogical or = assertInstanceOf(
				FilterNaryLogical.class,
				optimize("appointment-type eq A or location eq Location/1 or appointment-type eq B"));
		assertEquals(2, or.getOperands().size());
		assertEquals(List.of("A", "B"), assertInstanceOf(FilterParameterOrList.class, or.getOperands().get(0)).getValues());
		assertInstanceOf(SearchFilterParser.FilterParameter.class, or.getOperands().get(1));
	}

	@Test
	public void testDifferentParameters_NotMerged() throws Exception {
		FilterNaryLogical or = assertInstanceOf(
				FilterNaryLogical.class, optimize("appointment-type eq A or status eq B"));
		assertEquals(2, or.getOperands().size());
		assertTrue(or.getOperands().stream().allMatch(t -> t instanceof SearchFilterParser.FilterParameter));
	}

	@Test
	public void testNotEqual_NotMerged() throws Exception {
		FilterNaryLogical or = assertInstanceOf(
				FilterNaryLogical.class, optimize("appointment-type ne A or appointment-type ne B"));
		assertEquals(2, or.getOperands().size());
		assertTrue(or.getOperands().stream().allMatch(t -> t instanceof SearchFilterParser.FilterParameter));
	}

	@Test
	public void testAndOfSameParameter_NotMerged() throws Exception {
		FilterNaryLogical and = assertInstanceOf(
				FilterNaryLogical.class, optimize("appointment-type eq A and appointment-type eq B"));
		assertEquals(SearchFilterParser.FilterLogicalOperation.and, and.getOperation());
		assertEquals(2, and.getOperands().size());
		assertTrue(and.getOperands().stream().allMatch(t -> t instanceof SearchFilterParser.FilterParameter));
	}

	@Test
	public void testChainedPath_NotMerged() throws Exception {
		FilterNaryLogical or =
				assertInstanceOf(FilterNaryLogical.class, optimize("location.name eq A or location.name eq B"));
		assertEquals(2, or.getOperands().size());
	}

	@Test
	public void testUnknownOrNonTokenParameter_NotMerged() throws Exception {
		FilterNaryLogical unknown =
				assertInstanceOf(FilterNaryLogical.class, optimize("mystery eq A or mystery eq B"));
		assertEquals(2, unknown.getOperands().size());
		FilterNaryLogical date = assertInstanceOf(
				FilterNaryLogical.class, optimize("date eq ge2024-01-01 or date eq ge2024-02-01"));
		assertEquals(2, date.getOperands().size());
	}

	@Test
	public void testIdenticalOperands_AreDeduplicated() throws Exception {
		assertInstanceOf(SearchFilterParser.FilterParameter.class, optimize("status eq A or status eq A"));
		assertInstanceOf(SearchFilterParser.FilterParameter.class, optimize("status eq A and status eq A"));
		FilterParameterOrList orList = assertInstanceOf(
				FilterParameterOrList.class, optimize("status eq A or status eq B or status eq A"));
		assertEquals(List.of("A", "B"), orList.getValues());
	}

	@Test
	public void testString_SameOperatorMerged_DifferentOperatorsNot() throws Exception {
		FilterParameterOrList merged = assertInstanceOf(FilterParameterOrList.class, optimize("name sw a or name sw b"));
		assertEquals(SearchFilterParser.CompareOperation.sw, merged.getOperation());
		FilterNaryLogical mixed = assertInstanceOf(FilterNaryLogical.class, optimize("name sw a or name eq b"));
		assertEquals(2, mixed.getOperands().size());
		assertInstanceOf(FilterNaryLogical.class, optimize("name ne a or name ne b"));
	}

	@Test
	public void testReference_EqMerged() throws Exception {
		FilterParameterOrList merged = assertInstanceOf(
				FilterParameterOrList.class, optimize("location eq Location/1 or location eq Location/2"));
		assertEquals(List.of("Location/1", "Location/2"), merged.getValues());
	}

	@Test
	public void testMixedOperatorsKeepParserShape() throws Exception {
		// The parser has no precedence: "a and b or c" is and(a, or(b, c)). That must be preserved.
		FilterNaryLogical and = assertInstanceOf(
				FilterNaryLogical.class, optimize("location eq Location/1 and status eq A or status eq B"));
		assertEquals(SearchFilterParser.FilterLogicalOperation.and, and.getOperation());
		assertEquals(2, and.getOperands().size());
		assertInstanceOf(SearchFilterParser.FilterParameter.class, and.getOperands().get(0));
		assertEquals(
				List.of("A", "B"),
				assertInstanceOf(FilterParameterOrList.class, and.getOperands().get(1)).getValues());
	}

	@Test
	public void testNotOperator_ReturnsOriginalTree() throws Exception {
		SearchFilterParser.BaseFilter parsed = parse("status eq A not status eq B");
		assertSame(parsed, myOptimizer.optimize(parsed, myContext));
	}

	@Test
	public void testTooManyLeaves_ReturnsOriginalTree() throws Exception {
		String tooMany = IntStream.range(0, SearchFilterOptimizer.MAX_LEAVES + 1)
				.mapToObj(i -> "status eq V" + i)
				.collect(Collectors.joining(" or "));
		SearchFilterParser.BaseFilter parsed = parse(tooMany);
		assertSame(parsed, myOptimizer.optimize(parsed, myContext));

		String atLimit = IntStream.range(0, SearchFilterOptimizer.MAX_LEAVES)
				.mapToObj(i -> "status eq V" + i)
				.collect(Collectors.joining(" or "));
		FilterParameterOrList merged = assertInstanceOf(FilterParameterOrList.class, optimize(atLimit));
		assertEquals(SearchFilterOptimizer.MAX_LEAVES, merged.getValues().size());
	}

	@Test
	public void testRuleException_ReturnsOriginalTree() throws Exception {
		SearchFilterOptimizer broken = new SearchFilterOptimizer(List.of((node, ctx) -> {
			throw new IllegalStateException("boom");
		}));
		SearchFilterParser.BaseFilter parsed = parse("status eq A or status eq B");
		assertSame(parsed, broken.optimize(parsed, myContext));
	}

	@Test
	public void testOptimizeIsIdempotent() throws Exception {
		String filter = "(location eq Location/1) and (date eq ge2024-01-01) and "
				+ "((appointment-type eq A) or (appointment-type eq B) or (status eq C))";
		SearchFilterParser.BaseFilter once = optimize(filter);
		SearchFilterParser.BaseFilter twice = myOptimizer.optimize(once, myContext);
		assertEquals(once.toString(), twice.toString());
	}
}
